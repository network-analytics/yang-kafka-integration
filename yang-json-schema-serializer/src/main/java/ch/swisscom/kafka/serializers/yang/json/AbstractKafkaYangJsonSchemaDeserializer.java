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
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.json.jackson.Jackson;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDe;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.config.ConfigException;
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

  protected YangDataDocument deserialize(
      boolean includeSchemaAndVersion,
      String topic,
      Boolean isKey,
      Headers headers,
      byte[] payload) {
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
      try {
        id = Integer.parseInt(new String(serializedSchemaId, StandardCharsets.UTF_8).trim());
      } catch (NumberFormatException nfe) {
        id = ByteBuffer.wrap(serializedSchemaId).getInt();
      }
    } catch (Exception e) {
      System.out.println(
          "[SCHEMA-ID ERROR] Failed to read schema-id header: "
              + e.getClass().getSimpleName()
              + ": "
              + e.getMessage());
      log.warn("Failed to read schema-id header: {}", e.getMessage());
      return null;
    }

    // ── YANG Library path (deviations + features applied) ─────────────────────
    if (yangLibraryCacheBuilder != null) {
      System.out.println("[Deserializer] YANG Library path — schema-id=" + id);
      return deserializeWithYangLibrary(id, topic, isKey, payload);
    }

    // ── Legacy Schema-Registry-only path ──────────────────────────────────────
    System.out.println("[Deserializer] Legacy SR path — schema-id=" + id);
    return deserializeWithSchemaRegistry(id, topic, isKey, headers, payload);
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
  private YangDataDocument deserializeWithYangLibrary(
      int schemaId, String topic, Boolean isKey, byte[] payload) {
    try {
      String subject =
          isKey == null || strategyUsesSchema(isKey)
              ? getContextName(topic)
              : subjectName(topic, isKey, null);

      YangSchemaContext ctx = yangLibraryCacheBuilder.getOrBuild(schemaId, subject);
      if (ctx == null) {
        System.out.println(
            "[Deserializer] ctx=null for schema-id=" + schemaId + " — record skipped");
        log.warn("No schema context available for schema-id={}. Skipping record.", schemaId);
        return null;
      }
      JsonNode jsonNode = objectMapper.readTree(payload);
      log.debug("YANG Encoded Message for schema-id={}: {}", schemaId, jsonNode);

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
              System.out.println(msg);
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
          // Warn but continue — UNKNOWN_ELEMENT typically means a vendor/augmenting module
          // or an identity module (iana-if-type, ietf-udp-notif-transport, etc.) is absent
          // from Schema Registry. Partial schemas are normal in telemetry deployments.
          // Records are NOT dropped; the document is returned as-is with the warnings above.
          System.out.println(
              "[UNKNOWN_ELEMENT] schema-id="
                  + schemaId
                  + " — continuing despite missing module references (partial schema):\n  "
                  + String.join("\n  ", unknownElementMessages));
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
          System.out.println(
              "Deserialization failed for schema-id="
                  + schemaId
                  + " — parse errors:\n  "
                  + errors
                  + "\nPayload: "
                  + new String(payload, java.nio.charset.StandardCharsets.UTF_8));
          log.warn(
              "YANG JSON parse errors for schema-id={} — message skipped:\n  {}", schemaId, errors);
          return null;
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
          System.out.println(
              "Deserialization failed for schema-id="
                  + schemaId
                  + " — validation errors:\n  "
                  + errors
                  + "\nPayload: "
                  + new String(payload, java.nio.charset.StandardCharsets.UTF_8));
          log.warn(
              "YANG JSON validation errors for schema-id={} — message skipped:\n  {}",
              schemaId,
              errors);
          return null;
        }
      }

      if (doc == null) {
        log.warn(
            "YANG JSON parser returned null doc for schema-id={} — root element not in schema context. Skipping record.",
            schemaId);
        return null;
      }
      log.debug("[SUCCESS] schema-id={} deserialized OK", schemaId);
      System.out.println("[SUCCESS] schema-id=" + schemaId + " deserialized OK");
      return doc;
    } catch (Exception e) {
      System.out.println(
          "[EXCEPTION] schema-id="
              + schemaId
              + ": "
              + e.getClass().getSimpleName()
              + ": "
              + e.getMessage());
      e.printStackTrace(System.out);
      log.error(
          "Error deserializing YANG JSON via YANG Library for schema-id={}: {}",
          schemaId,
          e.getMessage(),
          e);
      return null;
    }
  }

  /**
   * Legacy path: builds schema context purely from Confluent Schema Registry responses. Deviations
   * and features declared in the router's YANG Library are NOT applied.
   */
  private YangDataDocument deserializeWithSchemaRegistry(
      int id, String topic, Boolean isKey, Headers headers, byte[] payload) {
    if (schemaRegistry == null) {
      log.error(
          "SchemaRegistryClient not found. You need to configure the deserializer"
              + " or use deserializer constructor with SchemaRegistryClient.");
      return null;
    }
    try {
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
          log.warn(
              "YANG JSON does not match YANG schema {}, skipping record. Error: {}",
              schema.canonicalString(),
              e.getMessage());
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
      log.warn("Timeout deserializing YANG-JSON message for id {}: {}", id, e.getMessage());
      return null;
    } catch (IOException | RuntimeException e) {
      log.warn("Error deserializing YANG-JSON message for id {}: {}", id, e.getMessage());
      return null;
    } catch (RestClientException e) {
      log.warn("Error retrieving YANG schema for id {}: {}", id, e.getMessage());
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
