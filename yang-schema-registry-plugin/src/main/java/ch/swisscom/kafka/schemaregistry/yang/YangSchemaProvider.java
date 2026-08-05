/*
 * Copyright 2023 Swisscom (Schweiz) AG.
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

package ch.swisscom.kafka.schemaregistry.yang;

import ch.swisscom.kafka.schemaregistry.util.BoundedCache;
import ch.swisscom.kafka.schemaregistry.util.ParseErrorReason;
import ch.swisscom.kafka.schemaregistry.util.YangSchemaProviderMetrics;
import io.confluent.kafka.schemaregistry.AbstractSchemaProvider;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import java.io.File;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference;
import org.dom4j.Document;
import org.dom4j.io.SAXReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yangcentral.yangkit.common.api.exception.Severity;
import org.yangcentral.yangkit.comparator.CompatibilityRules;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.model.api.stmt.Import;
import org.yangcentral.yangkit.model.api.stmt.Module;
import org.yangcentral.yangkit.parser.YangParserException;
import org.yangcentral.yangkit.register.YangStatementImplRegister;
import org.yangcentral.yangkit.register.YangStatementRegister;

public class YangSchemaProvider extends AbstractSchemaProvider {

  public static final String YANG_COMPARATOR_RULES_CONFIG = "yang.comparator.rules.path";

  public static final String YANG_REFERENCE_CACHE_MAX_SIZE = "yang.reference.cache.max.size";
  public static final String YANG_PARSED_SCHEMA_CACHE_MAX_SIZE = "yang.parsed.schema.cache.max.size";
  public static final String YANG_CACHE_IDLE_TIMEOUT_MINUTES = "yang.cache.idle.timeout.minutes";

  private static final int DEFAULT_YANG_REFERENCE_CACHE_MAX_SIZE = 1000;
  private static final int DEFAULT_YANG_PARSED_SCHEMA_CACHE_MAX_SIZE = 1000;
  private static final int DEFAULT_YANG_CACHE_IDLE_TIMEOUT_MINUTES = 100;

  private static final String YANG_COMPARATOR_DEFAULT_RULES = "default-rules.xml";
  private static final Logger log = LoggerFactory.getLogger(YangSchemaProvider.class);

  private record ReferenceCacheKey(String refName, String refSchema) {}
  private final BoundedCache<ReferenceCacheKey, Module> referenceCache;

  private record ParsedSchemaCacheKey(String subject, String schemaString, List<SchemaReference> references) {}
  private final BoundedCache<ParsedSchemaCacheKey, YangSchema> parsedSchemaCache;

  private YangSchemaProviderMetrics metrics;

  public YangSchemaProvider() {
    URL inputStream = YangSchema.class.getClassLoader().getResource(YANG_COMPARATOR_DEFAULT_RULES);
    try {
      SAXReader reader = SAXReader.createDefault();
      Document document = reader.read(inputStream);
      CompatibilityRules.getInstance().deserialize(document);
    } catch (Exception e) {
      throw new IllegalArgumentException("Couldn't load comparator rules", e);
    }
    YangStatementImplRegister.registerImpl();

    int referenceCacheMaxSize = parseIntProperty(
            YANG_REFERENCE_CACHE_MAX_SIZE, DEFAULT_YANG_REFERENCE_CACHE_MAX_SIZE);
    int parsedSchemaCacheMaxSize = parseIntProperty(
            YANG_PARSED_SCHEMA_CACHE_MAX_SIZE, DEFAULT_YANG_PARSED_SCHEMA_CACHE_MAX_SIZE);
    long cacheIdleTimeoutMillis = Duration.ofMinutes(
            parseIntProperty(YANG_CACHE_IDLE_TIMEOUT_MINUTES, DEFAULT_YANG_CACHE_IDLE_TIMEOUT_MINUTES)).toMillis();

    this.referenceCache = new BoundedCache<>(
            referenceCacheMaxSize,
            cacheIdleTimeoutMillis,
            () -> {
              metrics.recordReferenceCacheEviction();
            });

    this.parsedSchemaCache = new BoundedCache<>(
            parsedSchemaCacheMaxSize,
            cacheIdleTimeoutMillis,
            () -> {
              metrics.recordParsedSchemaCacheEviction();
            });

    this.metrics = new YangSchemaProviderMetrics(
            referenceCache::size,
            parsedSchemaCache::size
            );
  }

  private static int parseIntProperty(String propertyName, int defaultValue) {
    String value = System.getProperty(propertyName);
    if (value == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      log.warn("Invalid value for -D{}: '{}', falling back to default {}",
          propertyName, value, defaultValue);
      return defaultValue;
    }
  }

  @Override
  public void configure(Map<String, ?> configs) {
    super.configure(configs);
    Document document;
    try {
      if (configs.containsKey(YangSchemaProvider.YANG_COMPARATOR_RULES_CONFIG)) {
        String rulesPath = (String) configs.get(YangSchemaProvider.YANG_COMPARATOR_RULES_CONFIG);
        SAXReader reader = SAXReader.createDefault();
        document = reader.read(new File(rulesPath));
      } else {
        URL rulesStream =
            YangSchema.class.getClassLoader().getResource(YANG_COMPARATOR_DEFAULT_RULES);
        SAXReader reader = SAXReader.createDefault();
        document = reader.read(rulesStream);
      }
      CompatibilityRules.getInstance().deserialize(document);
    } catch (Exception e) {
      throw new IllegalArgumentException("Couldn't load comparator rules", e);
    }
  }

  @Override
  public String schemaType() {
    return YangSchema.TYPE;
  }

  @Override
  public ParsedSchema parseSchemaOrElseThrow(Schema schema, boolean isNew, boolean normalize) {
    metrics.recordRequest();

    ParsedSchemaCacheKey cacheKey = new ParsedSchemaCacheKey(schema.getSubject(), schema.getSchema(), schema.getReferences());

    AtomicBoolean cacheHit = new AtomicBoolean(true);
    AtomicReference<String> metricsKeyRef = new AtomicReference<>(schema.getSubject());
    YangSchema parsedYangSchema = parsedSchemaCache.getOrCreate(cacheKey, () -> {
      cacheHit.set(false);
      return parseYangSchema(schema, metricsKeyRef);
    });

    if (cacheHit.get()) {
      log.debug("Re-using cached parsed schema: {}", schema.getSubject());
      metrics.recordParsedSchemaCacheHit();
    }
    return parsedYangSchema;
  }

  private YangSchema parseYangSchema(Schema schema, AtomicReference<String> metricsKeyRef) {
    Map<String, String> resolvedReferences;
    try {
      resolvedReferences = resolveReferences(schema);
    } catch (Exception e) {
      log.error("Failed to resolve references for subject {}: {}: {}",
          schema.getSubject(), e.getClass().getSimpleName(), e.getMessage());
      metrics.recordParseError(ParseErrorReason.UNRESOLVABLE_REFERENCE);
      throw new YangSchemaException("Failed to resolve schema references for subject " + schema.getSubject(), e);
    }

    try {
      YangSchemaContext context = YangStatementRegister.getInstance().getSchemeContextInstance();

      for (Map.Entry<String, String> entry : resolvedReferences.entrySet()) {
        String refName = entry.getKey();
        String refSchema = entry.getValue();

        ReferenceCacheKey referenceCacheKey = new ReferenceCacheKey(refName, refSchema);
        Module cachedModule = referenceCache.get(referenceCacheKey);

        if (cachedModule != null) {
          log.debug("Re-using cached reference module: {}, refSchema: {}", refName, refSchema);
          metrics.recordReferenceCacheHit();
          context.addModule(cachedModule);
        } else {
          log.debug("Parsing module from raw, and caching it: {}, refSchema: {}", refName, refSchema);
          metrics.recordReferenceCacheMiss();
          Module parsedModule = YangSchemaUtils.parseYangString(refName, refSchema, context);

          if (parsedModule != null) {
            referenceCache.put(referenceCacheKey, parsedModule);
          }
        }
      }

      // Parse main schema
      Module rootModule = YangSchemaUtils.parseSchema(schema, context);
      String metricsModuleName = rootModule.getModuleId().getModuleName();
      metricsKeyRef.set(metricsModuleName);

      metrics.recordParsedSchemaCacheMiss();

      // cheap check before context.validation()
      for (Import imported : rootModule.getImports()) {
        // AH: do we need to resolve imports recursively?! Assuming this check was done on each one
        if (!resolvedReferences.containsKey(imported.getArgStr())) {
          throw new YangSchemaException("Unresolved import: " + imported);
        }
      }

      var result = context.validate();
      if (!result.isOk()) {
        // YANGKit is not able to have complete validation context, this is only relevant for data
        // validation which is not performed by the schema registry.
        boolean hasValidationError = false;
        for (var rec : result.getRecords()) {
          if (rec.getSeverity().equals(Severity.ERROR)) {
            hasValidationError = true;
            log.debug("Invalid YANG validation context for subject {}, ignored for now, {}",
                    schema.getSubject(), rec.getErrorMsg().getMessage());
          }
        }
        if (hasValidationError) {
          metrics.recordParseError(ParseErrorReason.VALIDATION_ERROR);
        }
      }
      context.getParseResult().clear();

      return new YangSchema(
          schema.getSchema(),
          context,
          rootModule,
          schema.getReferences(),
          resolvedReferences,
          metrics);
    } catch (YangParserException e) {
      log.error("Error parsing Yang Schema for subject {}: {}: {}",
          schema.getSubject(), e.getClass().getSimpleName(), e.getMessage());
      metrics.recordParseError(ParseErrorReason.PARSE_SCHEMA);
      throw new YangSchemaException("Invalid Yang " + schema.getSchema(), e);
    } catch (YangSchemaException e) {
      log.error("Unresolved import for subject {}: {}", schema.getSubject(), e.getMessage());
      metrics.recordParseError(ParseErrorReason.UNRESOLVED_IMPORTS);
      throw e;
    } catch (Exception e) {
      log.error("Error parsing Yang Schema for subject {}: {}", schema.getSubject(), e.getMessage());
      metrics.recordParseError(ParseErrorReason.UNEXPECTED);
      throw e;
    }
  }
}
