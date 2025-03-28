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

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import java.io.IOException;
import java.util.Map;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;
import org.yangcentral.yangkit.data.api.model.YangDataDocument;

public class KafkaYangJsonSchemaDeserializer<T> extends AbstractKafkaYangJsonSchemaDeserializer
    implements Deserializer<YangDataDocument> {

  /** Constructor used by Kafka consumer. */
  public KafkaYangJsonSchemaDeserializer() {}

  public KafkaYangJsonSchemaDeserializer(SchemaRegistryClient client) {
    this.schemaRegistry = client;
    this.ticker = ticker(client);
  }

  public KafkaYangJsonSchemaDeserializer(SchemaRegistryClient client, Map<String, ?> props) {
    this(client, props, false);
  }

  public KafkaYangJsonSchemaDeserializer(
      SchemaRegistryClient client, Map<String, ?> props, boolean isKey) {
    this.schemaRegistry = client;
    configure(deserializerConfig(props), isKey);
  }

  @Override
  public void configure(Map<String, ?> props, boolean isKey) {
    configure(new KafkaYangJsonSchemaDeserializerConfig(props), isKey);
  }

  protected void configure(KafkaYangJsonSchemaDeserializerConfig config, boolean isKey) {
    this.isKey = isKey;
    configure(config, YangDataDocument.class);
  }

  @Override
  public YangDataDocument deserialize(String topic, byte[] data) {
    return deserialize(topic, null, data);
  }

  @Override
  public YangDataDocument deserialize(String topic, Headers headers, byte[] bytes) {
    return deserialize(false, topic, isKey, headers, bytes);
  }

  @Override
  public void close() {
    try {
      super.close();
    } catch (IOException e) {
      throw new RuntimeException("Exception while closing deserializer", e);
    }
  }
}
