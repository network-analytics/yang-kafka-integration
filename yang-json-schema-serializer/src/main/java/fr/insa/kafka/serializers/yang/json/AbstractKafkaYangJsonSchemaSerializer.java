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

package fr.insa.kafka.serializers.yang.json;

import ch.swisscom.kafka.schemaregistry.yang.YangSchema;
import ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.rest.entities.RuleMode;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.json.jackson.Jackson;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.yangcentral.yangkit.model.api.codec.YangCodecException;

public abstract class AbstractKafkaYangJsonSchemaSerializer<T> extends AbstractKafkaSchemaSerDe {

  public static final String SCHEMA_ID_KEY = "schema-id";
  protected boolean normalizeSchema;
  protected boolean autoRegisterSchema;
  protected int useSchemaId = -1;
  protected boolean idCompatStrict;
  protected boolean validate;

  protected ObjectMapper objectMapper = Jackson.newObjectMapper();

  protected void configure(KafkaYangJsonSchemaSerializerConfig config) {
    configureClientProperties(config, new YangSchemaProvider());
    this.normalizeSchema = config.normalizeSchema();
    this.autoRegisterSchema = config.autoRegisterSchema();
    this.useSchemaId = config.useSchemaId();
    this.idCompatStrict = config.getIdCompatibilityStrict();
    this.validate =
        config.getBoolean(KafkaYangJsonSchemaSerializerConfig.YANG_JSON_FAIL_INVALID_SCHEMA);
  }

  protected byte[] serializeImpl(
      String subject, String topic, Headers headers, T object, YangSchema schema)
      throws InvalidConfigurationException {
    if (schemaRegistry == null) {
      throw new InvalidConfigurationException(
          "SchemaRegistryClient not found. You need to configure the serializer "
              + "or use serializer constructor with SchemaRegistryClient.");
    }
    if (object == null) {
      return null;
    }
    String restClientErrorMsg = "";
    try {
      int id;
      if (autoRegisterSchema) {
        restClientErrorMsg = "Error registering YANG schema: ";
        id = schemaRegistry.register(subject, schema, normalizeSchema);
      } else if (useSchemaId >= 0) {
        restClientErrorMsg = "Error retrieving schema ID";
        schema =
            (YangSchema) lookupSchemaBySubjectAndId(subject, useSchemaId, schema, idCompatStrict);
        id = schemaRegistry.getId(subject, schema);
      } else {
        restClientErrorMsg = "Error retrieving YANG schema: ";
        id = schemaRegistry.getId(subject, schema, normalizeSchema);
      }
      headers.add(SCHEMA_ID_KEY, ByteBuffer.allocate(idSize).putInt(id).array());
      headers.add("content-type", "application/yang-data+json".getBytes(StandardCharsets.UTF_8));
      object = (T) executeRules(subject, topic, headers, RuleMode.WRITE, null, schema, object);
      if (validate) {
        validateYangJson(object, schema);
      }

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(objectMapper.writeValueAsBytes(object));
      byte[] bytes = out.toByteArray();
      out.close();
      return bytes;
    } catch (YangCodecException | IOException e) {
      throw new SerializationException("Error serializing JSON message", e);
    } catch (RestClientException e) {
      throw toKafkaException(e, restClientErrorMsg + schema);
    } finally {
      postOp(object);
    }
  }

  protected void validateYangJson(T object, YangSchema schema)
      throws SerializationException, YangCodecException {
    JsonNode jsonNode = objectMapper.convertValue(object, JsonNode.class);
    schema.validate(jsonNode);
  }
}
