/*
 * Copyright 2025 INSA Lyon.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ch.swisscom.kafka.serializers.yang.json;

import ch.swisscom.kafka.schemaregistry.yang.YangSchema;
import ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.rest.entities.RuleMode;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.json.jackson.Jackson;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDe;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yangcentral.yangkit.data.api.model.YangDataDocument;
import org.yangcentral.yangkit.model.api.codec.YangCodecException;

public abstract class AbstractKafkaYangJsonSchemaDeserializer<T> extends AbstractKafkaSchemaSerDe {

  private static final Logger log =
      LoggerFactory.getLogger(AbstractKafkaYangJsonSchemaDeserializer.class);

  protected ObjectMapper objectMapper = Jackson.newObjectMapper();
  protected boolean validate;

  protected void configure(KafkaYangJsonSchemaDeserializerConfig config, Class<?> type) {
    configureClientProperties(config, new YangSchemaProvider());

    boolean failUnknownProperties =
        config.getBoolean(KafkaYangJsonSchemaDeserializerConfig.YANG_JSON_FAIL_UNKNOWN_PROPERTIES);
    this.objectMapper.configure(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, failUnknownProperties);
    this.validate =
        config.getBoolean(KafkaYangJsonSchemaDeserializerConfig.YANG_JSON_FAIL_INVALID_SCHEMA);
  }

  protected KafkaYangJsonSchemaDeserializerConfig deserializerConfig(Map<String, ?> props) {
    try {
      return new KafkaYangJsonSchemaDeserializerConfig(props);
    } catch (ConfigException e) {
      throw new ConfigException(e.getMessage());
    }
  }

  public ObjectMapper objectMapper() {
    return objectMapper;
  }

  protected T deserialize(byte[] payload) {
    return (T) deserialize(false, null, isKey, payload);
  }

  protected Object deserialize(
      boolean includeSchemaAndVersion, String topic, Boolean isKey, byte[] payload) {
    return deserialize(includeSchemaAndVersion, topic, isKey, null, payload);
  }

  protected YangDataDocument deserialize(
      boolean includeSchemaAndVersion, String topic, Boolean isKey, Headers headers, byte[] payload)
      throws SerializationException, InvalidConfigurationException {
    if (schemaRegistry == null) {
      log.error(
          "SchemaRegistryClient not found. You need to configure the deserializer "
              + "or use deserializer constructor with SchemaRegistryClient.");
      return null;
    }

    if (payload == null) {
      return null;
    }

    int id = -1;
    try {
      byte[] serializedSchemaId =
          headers.lastHeader(AbstractKafkaYangJsonSchemaSerializer.SCHEMA_ID_KEY).value();
      if (serializedSchemaId.length == 4) {
        id = ByteBuffer.wrap(serializedSchemaId).getInt();
      } else {
        id = Integer.parseInt(new String(serializedSchemaId, StandardCharsets.UTF_8));
      }

      String subject =
          isKey == null || strategyUsesSchema(isKey)
              ? getContextName(topic)
              : subjectName(topic, isKey, null);
      YangSchema schema = ((YangSchema) schemaRegistry.getSchemaBySubjectAndId(subject, id));

      ExtendedSchema readerSchema = null;
      if (metadata != null) {
        readerSchema = getLatestWithMetadata(subject);
      } else if (useLatestVersion) {
        readerSchema = lookupLatestVersion(subject, schema, false);
      }
      if (readerSchema != null) {
        subject = subjectName(topic, isKey, schema);
        schema = schemaForDeserialize(id, schema, subject, isKey);
        Integer version = schemaVersion(topic, isKey, id, subject, schema, null);
        schema = schema.copy(version);
      }
      List<Migration> migrations = Collections.emptyList();
      if (readerSchema != null) {
        migrations = getMigrations(subject, schema, readerSchema.getSchema());
      }

      ByteBuffer buffer = ByteBuffer.wrap(payload);
      int length = buffer.limit();
      int start = buffer.position();

      JsonNode jsonNode = null;
      YangDataDocument yangDataDocument = null;
      if (!migrations.isEmpty()) {
        jsonNode = objectMapper.readValue(buffer.array(), start, length, JsonNode.class);
        jsonNode = (JsonNode) executeMigrations(migrations, subject, topic, headers, jsonNode);
      }

      if (readerSchema != null) {
        schema = (YangSchema) readerSchema.getSchema();
      }
      if (schema.ruleSet() != null && schema.ruleSet().hasRules(RuleMode.READ)) {
        if (jsonNode == null) {
          jsonNode = objectMapper.readValue(buffer.array(), start, length, JsonNode.class);
        }
        jsonNode =
            (JsonNode)
                executeRules(
                    subject, topic, headers, payload, RuleMode.READ, null, schema, jsonNode);
      }

      if (validate) {
        try {
          if (jsonNode == null) {
            jsonNode = objectMapper.readValue(buffer.array(), start, length, JsonNode.class);
          }
          yangDataDocument = schema.validate(jsonNode);
        } catch (YangCodecException e) {
          log.error(
              "YANG JSON {} does not match YANG schema {}: {}",
              jsonNode,
              schema.canonicalString(),
              e.getMessage(),
              e);
          return null;
        }
      }
      if (jsonNode == null) {
        jsonNode = objectMapper.readValue(buffer.array(), start, length, JsonNode.class);
      }
      if (yangDataDocument == null) {
        yangDataDocument = schema.createYangDataDocument(jsonNode);
      }
      return yangDataDocument;
    } catch (InterruptedIOException e) {
      log.error("Timeout deserializing YANG-JSON message for id {}: {}", id, e.getMessage(), e);
      return null;
    } catch (IOException | RuntimeException e) {
      log.error("Error deserializing YANG-JSON message for id {}: {}", id, e.getMessage(), e);
      return null;
    } catch (RestClientException e) {
      log.error("Error retrieving YANG schema for id {}: {}", id, e.getMessage(), e);
      return null;
    } finally {
      postOp(payload);
    }
  }

  private String subjectName(String topic, boolean isKey, YangSchema schemaFromRegistry) {
    return getSubjectName(topic, isKey, null, schemaFromRegistry);
  }

  private YangSchema schemaForDeserialize(
      int id, YangSchema schemaFromRegistry, String subject, boolean isKey)
      throws IOException, RestClientException {
    return (YangSchema) schemaRegistry.getSchemaBySubjectAndId(subject, id);
  }

  private Integer schemaVersion(
      String topic, boolean isKey, int id, String subject, YangSchema schema, Object value)
      throws IOException, RestClientException {
    YangSchema subjectSchema = (YangSchema) schemaRegistry.getSchemaBySubjectAndId(subject, id);
    return schemaRegistry.getVersion(subject, subjectSchema);
  }
}
