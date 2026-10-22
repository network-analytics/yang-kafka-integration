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
import io.confluent.kafka.schemaregistry.client.rest.entities.SubjectVersion;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.json.jackson.Jackson;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDe;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.Headers;
import org.yangcentral.yangkit.data.api.model.YangDataDocument;
import org.yangcentral.yangkit.model.api.codec.YangCodecException;

public abstract class AbstractKafkaYangJsonSchemaDeserializer<T> extends AbstractKafkaSchemaSerDe {

  static final int MAX_SUBJECT_VERSION_CACHE_ENTRIES = 1000;
  static final long SUBJECT_VERSION_CACHE_TTL_MILLIS = 5 * 60 * 1000L;
  private static final long PREFERRED_SUBJECT_REFRESH_INTERVAL_MILLIS = 30 * 1000L;

  private final Map<Integer, CachedSubjectVersions> idToSubjectVersionsCache =
      Collections.synchronizedMap(
          new LinkedHashMap<Integer, CachedSubjectVersions>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, CachedSubjectVersions> eldest) {
              return size() > MAX_SUBJECT_VERSION_CACHE_ENTRIES;
            }
          });

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

  protected Object deserialize(
      boolean includeSchemaAndVersion, String topic, Boolean isKey, Headers headers, byte[] payload)
      throws SerializationException, InvalidConfigurationException {
    if (schemaRegistry == null) {
      throw new InvalidConfigurationException(
          "SchemaRegistryClient not found. You need to configure the deserializer "
              + "or use deserializer constructor with SchemaRegistryClient.");
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
      Integer resolvedVersion = null;
      if (includeSchemaAndVersion) {
        SubjectVersion subjectVersion = subjectVersionForId(id, subject);
        if (subjectVersion != null) {
          subject = subjectVersion.getSubject();
          resolvedVersion = subjectVersion.getVersion();
        }
      }
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
        if (resolvedVersion == null) {
          resolvedVersion = schemaVersion(topic, isKey, id, subject, schema, null);
        }
        schema = schema.copy(resolvedVersion);
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
          throw new SerializationException(
              "YANG JSON " + jsonNode + " does not match YANG schema " + schema.canonicalString(),
              e);
        }
      }
      if (jsonNode == null) {
        jsonNode = objectMapper.readValue(buffer.array(), start, length, JsonNode.class);
      }
      if (yangDataDocument == null) {
        yangDataDocument = schema.createYangDataDocument(jsonNode);
      }
      if (includeSchemaAndVersion) {
        if (resolvedVersion == null) {
          resolvedVersion = schemaVersion(topic, isKey, id, subject, schema, null);
        }
        return new YangSchemaAndValue(
            id, subject, resolvedVersion, schema.references(), yangDataDocument);
      }
      return yangDataDocument;
    } catch (InterruptedIOException e) {
      throw new TimeoutException("Error deserializing YANG-JSON message for id " + id, e);
    } catch (IOException | RuntimeException e) {
      throw new SerializationException("Error deserializing YANG-JSON message for id " + id, e);
    } catch (RestClientException e) {
      throw toKafkaException(e, "Error retrieving YANG schema for id " + id);
    } finally {
      postOp(payload);
    }
  }

  private String subjectName(String topic, boolean isKey, YangSchema schemaFromRegistry) {
    return getSubjectName(topic, isKey, null, schemaFromRegistry);
  }

  private SubjectVersion subjectVersionForId(int id, String preferredSubject)
      throws IOException, RestClientException {
    long now = System.currentTimeMillis();
    CachedSubjectVersions cached = idToSubjectVersionsCache.get(id);
    boolean expired = cached != null && cached.isExpired(now);
    boolean preferredSubjectMissing =
        cached != null && preferredSubject != null && !cached.containsSubject(preferredSubject);
    boolean refreshForPreferredSubject =
        preferredSubjectMissing && cached.shouldRefreshForPreferredSubject(preferredSubject, now);

    if (cached == null || expired || refreshForPreferredSubject) {
      Collection<SubjectVersion> fetched = schemaRegistry.getAllVersionsById(id);
      if (fetched != null) {
        List<SubjectVersion> snapshot = immutableSubjectVersions(fetched);
        cached = new CachedSubjectVersions(snapshot, now);
        if (preferredSubject != null && !cached.containsSubject(preferredSubject)) {
          cached.recordPreferredSubjectMiss(preferredSubject, now);
        }
        idToSubjectVersionsCache.put(id, cached);
      } else if (expired) {
        idToSubjectVersionsCache.remove(id);
        cached = null;
      } else if (preferredSubjectMissing) {
        cached.recordPreferredSubjectMiss(preferredSubject, now);
      }
    }

    if (cached == null || cached.subjectVersions.isEmpty()) {
      return null;
    }
    for (SubjectVersion subjectVersion : cached.subjectVersions) {
      if (preferredSubject != null && preferredSubject.equals(subjectVersion.getSubject())) {
        return subjectVersion;
      }
    }
    return cached.subjectVersions.stream()
        .filter(subjectVersion -> subjectVersion.getSubject() != null)
        .filter(subjectVersion -> subjectVersion.getVersion() != null)
        .min(
            Comparator.comparing(SubjectVersion::getSubject)
                .thenComparing(SubjectVersion::getVersion))
        .orElse(null);
  }

  static List<SubjectVersion> immutableSubjectVersions(Collection<SubjectVersion> versions) {
    return List.copyOf(versions);
  }

  private static final class CachedSubjectVersions {
    private final List<SubjectVersion> subjectVersions;
    private final long loadedAtMillis;
    private String lastMissedPreferredSubject;
    private long lastPreferredSubjectRefreshMillis;

    private CachedSubjectVersions(List<SubjectVersion> subjectVersions, long loadedAtMillis) {
      this.subjectVersions = subjectVersions;
      this.loadedAtMillis = loadedAtMillis;
    }

    private boolean isExpired(long now) {
      return now - loadedAtMillis >= SUBJECT_VERSION_CACHE_TTL_MILLIS;
    }

    private boolean containsSubject(String subject) {
      return subjectVersions.stream().anyMatch(v -> Objects.equals(subject, v.getSubject()));
    }

    private boolean shouldRefreshForPreferredSubject(String subject, long now) {
      return !Objects.equals(lastMissedPreferredSubject, subject)
          || now - lastPreferredSubjectRefreshMillis >= PREFERRED_SUBJECT_REFRESH_INTERVAL_MILLIS;
    }

    private void recordPreferredSubjectMiss(String subject, long now) {
      lastMissedPreferredSubject = subject;
      lastPreferredSubjectRefreshMillis = now;
    }
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
