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

import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference;
import java.util.Collections;
import java.util.List;
import org.yangcentral.yangkit.data.api.model.YangDataDocument;

/**
 * Wraps a deserialized {@link YangDataDocument} together with the schema registry metadata (schema
 * id, subject, version and schema references) that was resolved while deserializing the record.
 * Returned by {@link KafkaYangJsonSchemaMetadataDeserializer} for consumers that need to propagate
 * schema identity alongside the payload.
 */
public class YangSchemaAndValue {

  private final int id;
  private final String subject;
  private final Integer version;
  private final List<SchemaReference> references;
  private final YangDataDocument value;

  public YangSchemaAndValue(
      int id,
      String subject,
      Integer version,
      List<SchemaReference> references,
      YangDataDocument value) {
    this.id = id;
    this.subject = subject;
    this.version = version;
    this.references =
        references == null ? Collections.emptyList() : Collections.unmodifiableList(references);
    this.value = value;
  }

  public int getId() {
    return id;
  }

  public String getSubject() {
    return subject;
  }

  public Integer getVersion() {
    return version;
  }

  /**
   * @return the list of YANG modules imported/referenced by the root schema, as registered in the
   *     schema registry. Empty if the schema declares no references.
   */
  public List<SchemaReference> getReferences() {
    return references;
  }

  public YangDataDocument getValue() {
    return value;
  }
}
