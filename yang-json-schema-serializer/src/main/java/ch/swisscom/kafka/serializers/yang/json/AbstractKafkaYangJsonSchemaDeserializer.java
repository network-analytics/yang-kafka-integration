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

import ch.swisscom.kafka.schemaregistry.yang.YangLibraryCacheBuilder;
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
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yangcentral.yangkit.common.api.exception.ErrorTag;
import org.yangcentral.yangkit.common.api.validate.ValidatorResult;
import org.yangcentral.yangkit.common.api.validate.ValidatorResultBuilder;
import org.yangcentral.yangkit.data.api.model.YangDataDocument;
import org.yangcentral.yangkit.data.codec.json.YangDataDocumentJsonParser;
import org.yangcentral.yangkit.model.api.codec.YangCodecException;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;

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
  private static final Logger log =
      LoggerFactory.getLogger(AbstractKafkaYangJsonSchemaDeserializer.class);

  protected ObjectMapper objectMapper = Jackson.newObjectMapper();
  protected boolean validate;

  /**
   * Non-null when {@code yang.library.cache.path} is configured. When present, deserialization uses
   * YANG Library (RFC 8525) with full deviation + feature support. When null, the legacy
   * Schema-Registry-only path is used.
   */
  private YangLibraryCacheBuilder yangLibraryCacheBuilder;

  protected void configure(KafkaYangJsonSchemaDeserializerConfig config, Class<?> type) {
    configureClientProperties(config, new YangSchemaProvider());

    boolean failUnknownProperties =
        config.getBoolean(KafkaYangJsonSchemaDeserializerConfig.YANG_JSON_FAIL_UNKNOWN_PROPERTIES);
    this.objectMapper.configure(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, failUnknownProperties);
    this.validate =
        config.getBoolean(KafkaYangJsonSchemaDeserializerConfig.YANG_JSON_FAIL_INVALID_SCHEMA);

    // Initialise the YANG Library cache builder if a cache path is configured.
    String cachePath =
        config.getString(KafkaYangJsonSchemaDeserializerConfig.YANG_LIBRARY_CACHE_PATH);
    if (cachePath != null && !cachePath.isBlank()) {
      File cacheRoot = new File(cachePath);
      cacheRoot.mkdirs();
      this.yangLibraryCacheBuilder = new YangLibraryCacheBuilder(schemaRegistry, cacheRoot);
      log.info("YANG Library mode enabled. Cache root: {}", cacheRoot.getAbsolutePath());
    } else {
      log.info("YANG Library mode disabled - using Schema Registry path only.");
    }
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

    // Extract schema-id from Kafka header.
    // NetGauze (Rust) writes it as a UTF-8 decimal string (e.g. "42").
    // yang-kafka-integration (Java) writes it as 4-byte big-endian binary.
    // We handle both formats.
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
      if (yangLibraryCacheBuilder != null) {
        return deserializeWithYangLibrary(
            includeSchemaAndVersion, id, topic, isKey, subject, payload);
      }
      return deserializeWithSchemaRegistry(
          includeSchemaAndVersion, id, topic, isKey, headers, payload);
    } catch (InterruptedIOException e) {
      throw new TimeoutException("Error deserializing YANG-JSON message for id " + id, e);
    } catch (IOException | RuntimeException e) {
      throw new SerializationException("Error deserializing YANG-JSON message for id " + id, e);
    } catch (RestClientException e) {
      throw toKafkaException(e, "Error retrieving YANG schema for id " + id);
    } catch (Exception e) {
      throw new SerializationException("Error deserializing YANG-JSON message for id " + id, e);
    } finally {
      postOp(payload);
    }
  }

  /**
   * Deserializes using YANG Library (RFC 8525).
   *
   * <p>On first call per schema-id:
   *
   * <ol>
   *   <li>All YANG modules are fetched from Confluent SR recursively.
   *   <li>Each module is scanned for {@code deviation} statements so vendor deviations are detected
   *       automatically.
   *   <li>Enabled features are read from SR {@code Metadata.tags["features"]}.
   *   <li>A {@code yang-lib.xml} + {@code modules/*.yang} directory is written to disk (same layout
   *       as NetGauze's local cache).
   *   <li>{@link org.yangcentral.yangkit.parser.YangYinParser#parseFromYangLibrary} builds and
   *       validates the {@link YangSchemaContext}.
   * </ol>
   *
   * Subsequent calls use the in-memory cached context.
   */
  private Object deserializeWithYangLibrary(
      boolean includeSchemaAndVersion,
      int schemaId,
      String topic,
      Boolean isKey,
      String subject,
      byte[] payload)
      throws Exception {
      YangSchemaContext ctx = yangLibraryCacheBuilder.getOrBuild(schemaId, subject);
      if (ctx == null) {
        throw new SerializationException(
            "YANG schema context is not available yet for schema-id " + schemaId);
      }
      JsonNode jsonNode = objectMapper.readTree(payload);

      ValidatorResultBuilder resultBuilder = new ValidatorResultBuilder();
      YangDataDocumentJsonParser parser = new YangDataDocumentJsonParser(ctx);
      YangDataDocument doc = parser.parse(jsonNode, resultBuilder);

      if (validate && doc != null) {
        // update() resolves leafrefs + defaults before validation.
        doc.update();

        // UNKNOWN_ELEMENT errors are logged as warnings but do not skip the record —
        // they occur when vendor or augmenting modules are absent from the schema registry
        // (partial schemas are normal in telemetry deployments).
        // All other error tags represent genuine data problems and skip the record.
        ValidatorResult parseResult = resultBuilder.build();
        boolean hasUnknownElements = false;
        List<String> unknownElementMessages = new ArrayList<>();
        if (parseResult.getRecords() != null) {
          for (var r : parseResult.getRecords()) {
            if (r.getErrorTag() == ErrorTag.UNKNOWN_ELEMENT) {
              hasUnknownElements = true;
              String msg =
                  "[UNKNOWN_ELEMENT] schema-id="
                      + schemaId
                      + " | path="
                      + r.getErrorPath()
                      + " | message="
                      + (r.getErrorMsg() != null ? r.getErrorMsg().getMessage() : "")
                      + " | badElement="
                      + r.getBadElement();
              unknownElementMessages.add(msg);
              log.warn(
                  "[UNKNOWN_ELEMENT] schema-id={} | path={} | message={} | badElement={}",
                  schemaId,
                  r.getErrorPath(),
                  r.getErrorMsg() != null ? r.getErrorMsg().getMessage() : "",
                  r.getBadElement());
            }
          }
        }
        if (hasUnknownElements) {
          log.warn(
              "[UNKNOWN_ELEMENT] schema-id={} — partial schema, {} unresolved elements. Record accepted.",
              schemaId,
              unknownElementMessages.size());
        }
        List<String> parseErrors =
            parseResult.getRecords() == null
                ? java.util.Collections.emptyList()
                : parseResult.getRecords().stream()
                    .filter(r -> r.getErrorTag() != ErrorTag.UNKNOWN_ELEMENT)
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.toList());
        if (!parseErrors.isEmpty()) {
          String errors = String.join("\n  ", parseErrors);
          throw new SerializationException(
            "YANG JSON parse errors for schema-id " + schemaId + ":\n  " + errors);
        }

        ValidatorResult validationResult = doc.validate();
        List<String> validationErrors =
            validationResult.getRecords() == null
                ? java.util.Collections.emptyList()
                : validationResult.getRecords().stream()
                    // Suppress DATA_MISSING only at the root path ("/") — those come from
                    // mandatory top-level nodes (nacm, yang-library, etc.) across the full
                    // device schema that are never present in a single telemetry message.
                    // DATA_MISSING deeper in the tree is a genuine data error.
                    .filter(
                        r ->
                            !(r.getErrorTag() == ErrorTag.DATA_MISSING
                                && r.getErrorPath()
                                    instanceof org.yangcentral.yangkit.common.api.AbsolutePath
                                && ((org.yangcentral.yangkit.common.api.AbsolutePath)
                                        r.getErrorPath())
                                    .isRootPath()))
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.toList());
        if (!validationErrors.isEmpty()) {
          String errors = String.join("\n  ", validationErrors);
          throw new SerializationException(
            "YANG JSON validation errors for schema-id " + schemaId + ":\n  " + errors);
        }
      }

      if (doc == null) {
        throw new SerializationException(
            "YANG JSON parser returned no document for schema-id " + schemaId);
      }
      if (includeSchemaAndVersion) {
        SubjectVersion subjectVersion = subjectVersionForId(schemaId, subject);
        Integer version = null;
        if (subjectVersion != null) {
          subject = subjectVersion.getSubject();
          version = subjectVersion.getVersion();
        }
        YangSchema schema = (YangSchema) schemaRegistry.getSchemaBySubjectAndId(subject, schemaId);
        if (version == null) {
          version = schemaVersion(topic, isKey, schemaId, subject, schema, null);
        }
        return new YangSchemaAndValue(schemaId, subject, version, schema.references(), doc);
      }
      return doc;
  }

  /**
   * Legacy path: builds schema context purely from Confluent Schema Registry responses. Deviations
   * and features declared in the router's YANG Library are NOT applied.
   */
  private Object deserializeWithSchemaRegistry(
      boolean includeSchemaAndVersion,
      int id,
      String topic,
      Boolean isKey,
      Headers headers,
      byte[] payload)
      throws IOException, RestClientException {
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
              "YANG JSON " + jsonNode + " does not match YANG schema " + schema.canonicalString(), e);
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
