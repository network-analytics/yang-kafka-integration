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

package ch.swisscom.kafka.formatter.yang.json;

import ch.swisscom.kafka.schemaregistry.yang.YangSchema;
import ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider;
import ch.swisscom.kafka.serializers.yang.json.AbstractKafkaYangJsonSchemaSerializer;
import ch.swisscom.kafka.serializers.yang.json.KafkaYangJsonSchemaSerializerConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.formatter.SchemaMessageReader;
import io.confluent.kafka.formatter.SchemaMessageSerializer;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.SchemaProvider;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.json.jackson.Jackson;
import java.io.IOException;
import java.util.Map;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.tools.api.RecordReader;
import org.everit.json.schema.ValidationException;

public class YangJsonSchemaMessageReader extends SchemaMessageReader<JsonNode>
    implements RecordReader {

  private static final ObjectMapper objectMapper = Jackson.newObjectMapper();

  /** Constructor needed by kafka console producer. */
  public YangJsonSchemaMessageReader() {}

  /** For testing only. */
  YangJsonSchemaMessageReader(
      String url,
      YangSchema keySchema,
      YangSchema valueSchema,
      String topic,
      boolean parseKey,
      boolean normalizeSchema,
      boolean autoRegister,
      boolean useLatest) {
    super(url, keySchema, valueSchema, topic, parseKey, normalizeSchema, autoRegister, useLatest);
  }

  @Override
  protected SchemaMessageSerializer<JsonNode> createSerializer(Serializer keySerializer) {
    return new YangJsonJsonSchemaSerializer(keySerializer);
  }

  @Override
  protected JsonNode readFrom(String jsonString, ParsedSchema schema) {
    try {
      return objectMapper.readTree(jsonString);
    } catch (IOException | ValidationException e) {
      throw new SerializationException(
          String.format("Error serializing yang-json %s", jsonString), e);
    }
  }

  @Override
  protected SchemaProvider getProvider() {
    return new YangSchemaProvider();
  }

  static class YangJsonJsonSchemaSerializer extends AbstractKafkaYangJsonSchemaSerializer<JsonNode>
      implements SchemaMessageSerializer<JsonNode> {

    protected final Serializer keySerializer;

    YangJsonJsonSchemaSerializer(Serializer keySerializer) {
      this.keySerializer = keySerializer;
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
      if (!configs.containsKey(KafkaYangJsonSchemaSerializerConfig.YANG_JSON_FAIL_INVALID_SCHEMA)) {
        ((Map<String, Object>) configs)
            .put(KafkaYangJsonSchemaSerializerConfig.YANG_JSON_FAIL_INVALID_SCHEMA, "true");
      }
      configure(new KafkaYangJsonSchemaSerializerConfig(configs));
    }

    @Override
    public Serializer getKeySerializer() {
      return keySerializer;
    }

    @Override
    public byte[] serializeKey(String topic, Headers headers, Object payload) {
      return keySerializer.serialize(topic, headers, payload);
    }

    @Override
    public byte[] serialize(
        String subject,
        String topic,
        boolean isKey,
        Headers headers,
        JsonNode object,
        ParsedSchema schema) {
      return super.serializeImpl(subject, topic, headers, object, (YangSchema) schema);
    }

    @Override
    public SchemaRegistryClient getSchemaRegistryClient() {
      return schemaRegistry;
    }
  }
}
