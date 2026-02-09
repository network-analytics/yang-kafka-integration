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

import ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider;
import ch.swisscom.kafka.serializers.yang.json.AbstractKafkaYangJsonSchemaDeserializer;
import ch.swisscom.kafka.serializers.yang.json.KafkaYangJsonSchemaDeserializerConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.formatter.SchemaMessageDeserializer;
import io.confluent.kafka.formatter.SchemaMessageFormatter;
import io.confluent.kafka.schemaregistry.SchemaProvider;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.json.jackson.Jackson;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;
import org.yangcentral.yangkit.data.api.model.YangDataDocument;

public class YangJsonSchemaMessageFormatter extends SchemaMessageFormatter<YangDataDocument> {

  private static final ObjectMapper objectMapper = Jackson.newObjectMapper();

  public YangJsonSchemaMessageFormatter() {}

  @Override
  protected SchemaMessageDeserializer createDeserializer(Deserializer keyDeserializer) {
    return new YangJsonSchemaMessageDeserializer(keyDeserializer);
  }

  @Override
  protected void writeTo(
      String topic, Boolean isKey, Headers headers, byte[] data, PrintStream output)
      throws IOException {
    YangDataDocument object = deserializer.deserialize(topic, isKey, headers, data);
    JsonNode jsonNode;
    try {
      jsonNode = objectMapper.readTree(object.getDocString());
      output.println(objectMapper.writeValueAsString(jsonNode));
    } catch (JsonProcessingException ignored) {
      output.println(objectMapper.writeValueAsString("error reading value"));
    }
  }

  @Override
  protected SchemaProvider getProvider() {
    return new YangSchemaProvider();
  }

  static class YangJsonSchemaMessageDeserializer extends AbstractKafkaYangJsonSchemaDeserializer
      implements SchemaMessageDeserializer<YangDataDocument> {

    protected final Deserializer keyDeserializer;

    YangJsonSchemaMessageDeserializer(Deserializer keyDeserializer) {
      this.keyDeserializer = keyDeserializer;
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
      if (!configs.containsKey(
          KafkaYangJsonSchemaDeserializerConfig.YANG_JSON_FAIL_INVALID_SCHEMA)) {
        ((Map<String, Object>) configs)
            .put(KafkaYangJsonSchemaDeserializerConfig.YANG_JSON_FAIL_INVALID_SCHEMA, "true");
        configure(deserializerConfig(configs), null);
      }
    }

    @Override
    public Deserializer getKeyDeserializer() {
      return keyDeserializer;
    }

    @Override
    public Object deserializeKey(String topic, Headers headers, byte[] payload) {
      return keyDeserializer.deserialize(topic, headers, payload);
    }

    @Override
    public YangDataDocument deserialize(
        String topic, Boolean isKey, Headers headers, byte[] payload)
        throws SerializationException {
      return super.deserialize(false, topic, isKey, headers, payload);
    }

    @Override
    public SchemaRegistryClient getSchemaRegistryClient() {
      return schemaRegistry;
    }
  }
}
