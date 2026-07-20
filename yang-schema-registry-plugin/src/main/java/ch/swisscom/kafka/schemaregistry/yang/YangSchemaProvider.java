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

import io.confluent.kafka.schemaregistry.AbstractSchemaProvider;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import java.io.File;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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

  public static final String SKIP_REFERENCE_PARSING = "yang.hotfix.skip-reference-parsing";
  public static final String SKIP_COMPATIBILITY_CHECK = "yang.hotfix.skip-compatibility-check";

  private static final String YANG_COMPARATOR_DEFAULT_RULES = "default-rules.xml";
  private static final Logger log = LoggerFactory.getLogger(YangSchemaProvider.class);

  private static final int DEFAULT_PARSED_SCHEMA_CACHE_MAX_SIZE = 1000;
  private static final int DEFAULT_REFERENCE_MODULE_CACHE_MAX_SIZE = 500;

  private static final int LINKED_HASH_MAP_DEFAULT_INITIAL_CAPACITY = 16;
  private static final float LINKED_HASH_MAP_DEFAULT_LOAD_FACTOR = 0.75f;
  private static final boolean LINKED_HASH_MAP_ACCESS_ORDER = true; // todo: true takes more MEM and less CPU, test more

  private record ReferenceCacheEntry(Module module, String schemaString) {}

  // Composite record keys
  private record ReferenceCacheKey(String refName, String refSchema) {}
  private record ParsedSchemaCacheKey(String schemaString, Map<String, String> resolvedReferences) {}

  // todo: test impact of having each individual cache
  private final Map<ReferenceCacheKey, ReferenceCacheEntry> referenceModuleCache;
  private final Map<ParsedSchemaCacheKey, YangSchema> parsedSchemaCache;

  private final YangSchemaProviderMetrics metrics;

  private boolean skipReferenceParsing = false;
  private boolean skipCompatibilityCheck = false;

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
    this.parsedSchemaCache = Collections.synchronizedMap(
      new LinkedHashMap<ParsedSchemaCacheKey, YangSchema>(
              LINKED_HASH_MAP_DEFAULT_INITIAL_CAPACITY,
              LINKED_HASH_MAP_DEFAULT_LOAD_FACTOR,
              LINKED_HASH_MAP_ACCESS_ORDER
      ) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ParsedSchemaCacheKey, YangSchema> eldest) {
          return size() > DEFAULT_PARSED_SCHEMA_CACHE_MAX_SIZE;
        }
      });
    this.referenceModuleCache = Collections.synchronizedMap(
      new LinkedHashMap<ReferenceCacheKey, ReferenceCacheEntry>(
              LINKED_HASH_MAP_DEFAULT_INITIAL_CAPACITY,
              LINKED_HASH_MAP_DEFAULT_LOAD_FACTOR,
              LINKED_HASH_MAP_ACCESS_ORDER
      ) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ReferenceCacheKey, ReferenceCacheEntry> eldest) {
          return size() > DEFAULT_REFERENCE_MODULE_CACHE_MAX_SIZE;
        }
      });
    this.metrics =
        new YangSchemaProviderMetrics(parsedSchemaCache::size, referenceModuleCache::size);
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

    this.skipReferenceParsing = Boolean.parseBoolean(System.getProperty(SKIP_REFERENCE_PARSING, "false"));
    this.skipCompatibilityCheck = Boolean.parseBoolean(System.getProperty(SKIP_COMPATIBILITY_CHECK, "false"));

    if (skipReferenceParsing && !skipCompatibilityCheck) {
      log.warn("[hotfix] skip-reference-parsing=true forces skip-compatibility-check=true");
      this.skipCompatibilityCheck = true;
    }

    log.info(
        "skip-reference-parsing: {}, skip-compatibility-check: {}, " +
        "parsed-schema-cache.max-size: {}, reference-module-cache.max-size: {}",
        skipReferenceParsing, skipCompatibilityCheck,
        DEFAULT_PARSED_SCHEMA_CACHE_MAX_SIZE, DEFAULT_REFERENCE_MODULE_CACHE_MAX_SIZE);
  }

  @Override
  public String schemaType() {
    return YangSchema.TYPE;
  }

  @Override
  public ParsedSchema parseSchemaOrElseThrow(Schema schema, boolean isNew, boolean normalize) {
    metrics.recordRequest();
    Map<String, String> resolvedReferences = resolveReferences(schema);

    ParsedSchemaCacheKey cacheKey = buildParsedSchemaCacheKey(schema.getSchema(), resolvedReferences);
    YangSchema cachedParsedSchema = parsedSchemaCache.get(cacheKey);
    if (cachedParsedSchema != null) {
      log.info("Re-using fully parsed schema from cache for subject {}", schema.getSubject());
      metrics.recordParsedSchemaCacheHit();
      return new YangSchema(
          cachedParsedSchema.canonicalString(),
          cachedParsedSchema.yangSchemaContext(),
          cachedParsedSchema.rawSchema(),
          schema.getReferences(),
          resolvedReferences,
          skipCompatibilityCheck);
    }
    metrics.recordParsedSchemaCacheMiss();

    YangSchemaContext context = YangStatementRegister.getInstance().getSchemeContextInstance();

    try {
      if (!skipReferenceParsing) {
        for (Map.Entry<String, String> entry : resolvedReferences.entrySet()) {
          String refName = entry.getKey();
          String refSchema = entry.getValue();

          ReferenceCacheKey referenceCacheKey = new ReferenceCacheKey(refName, refSchema);
          ReferenceCacheEntry cached = referenceModuleCache.get(referenceCacheKey);

          if (cached != null) {
            log.debug("Re-using cached reference module: {}, refSchema: {}", refName, refSchema);
            metrics.recordReferenceModuleCacheHit(refName);
            context.addModule(cached.module());
          } else {
            log.debug("Parsing from raw and caching reference module: {}, refSchema: {}", refName, refSchema);
            metrics.recordReferenceModuleCacheMiss(refName);
            int moduleCountBefore = context.getModules().size();
            YangSchemaUtils.parseYangString(refName, refSchema, context);

            // The newly parsed module is the last one just added in previous parseYangString() step.
            if (context.getModules().size() > moduleCountBefore) {
              Module parsedModule = context.getModules().get(context.getModules().size() - 1);
              referenceModuleCache.put(referenceCacheKey, new ReferenceCacheEntry(parsedModule, refSchema));
            }
          }
        }
      }

      // Parse main schema
      YangSchemaUtils.parseSchema(schema, context);

      if (!skipReferenceParsing) {
        // todo: duplicate?
        var result = context.validate();
        if (!result.isOk()) {
          // YANGKit is not able to have complete validation context, this is only relevant for data
          // validation which is not performed by the schema registry.
          for (var rec : result.getRecords()) {
            if (rec.getSeverity().equals(Severity.ERROR)) {
              // todo: add metrics?
              log.debug("Invalid YANG validation context for subject {}, ignored for now, {}",
                      schema.getSubject(), rec.getErrorMsg().getMessage());
            }
          }
        }
      }

      Module rootModule = context.getModules().get(context.getModules().size() - 1);
      for (Import imported : rootModule.getImports()) {
        // AH: do we need to resolve imports recursively?! Assuming this check was done on each one
        if (!resolvedReferences.containsKey(imported.getArgStr())) {
          throw new IllegalArgumentException("Unresolved import: " + imported);
        }
      }
      YangSchema yangSchema =
          new YangSchema(
              schema.getSchema(),
              context,
              rootModule,
              schema.getReferences(),
              resolvedReferences,
              skipCompatibilityCheck);
      parsedSchemaCache.put(cacheKey, yangSchema);
      return yangSchema;
    } catch (YangParserException e) {
      log.error("Error parsing Yang Schema", e);
      throw new IllegalArgumentException("Invalid Yang " + schema.getSchema(), e);
    } catch (Exception e) {
      log.error("Error parsing Yang Schema", e);
      throw e;
    }
  }

  private static ParsedSchemaCacheKey buildParsedSchemaCacheKey(
      String schemaString, Map<String, String> resolvedReferences) {
    return new ParsedSchemaCacheKey(schemaString, Map.copyOf(resolvedReferences));
  }
}
