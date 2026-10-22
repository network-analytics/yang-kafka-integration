/*
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

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import java.io.IOException;
import java.util.Map;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;

/**
 * Deserializes YANG-JSON Kafka records into a {@link YangSchemaAndValue}, exposing the resolved
 * schema id, subject, version and references alongside the validated {@link
 * org.yangcentral.yangkit.data.api.model.YangDataDocument} payload.
 *
 * <p>Use this deserializer instead of {@link KafkaYangJsonSchemaDeserializer} when downstream
 * consumers need to propagate schema registry metadata (e.g. for enrichment of the record before
 * publishing it further).
 */
public class KafkaYangJsonSchemaMetadataDeserializer extends AbstractKafkaYangJsonSchemaDeserializer
    implements Deserializer<YangSchemaAndValue> {

  /** Constructor used by Kafka consumer. */
  public KafkaYangJsonSchemaMetadataDeserializer() {}

  public KafkaYangJsonSchemaMetadataDeserializer(SchemaRegistryClient client) {
    this.schemaRegistry = client;
    this.ticker = ticker(client);
  }

  public KafkaYangJsonSchemaMetadataDeserializer(
      SchemaRegistryClient client, Map<String, ?> props) {
    this(client, props, false);
  }

  public KafkaYangJsonSchemaMetadataDeserializer(
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
    configure(config, YangSchemaAndValue.class);
  }

  @Override
  public YangSchemaAndValue deserialize(String topic, byte[] data) {
    return deserialize(topic, null, data);
  }

  @Override
  public YangSchemaAndValue deserialize(String topic, Headers headers, byte[] bytes) {
    return (YangSchemaAndValue) deserialize(true, topic, isKey, headers, bytes);
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
