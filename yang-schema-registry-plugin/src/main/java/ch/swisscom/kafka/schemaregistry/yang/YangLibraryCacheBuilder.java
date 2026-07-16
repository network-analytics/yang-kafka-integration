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

package ch.swisscom.kafka.schemaregistry.yang;

import com.netgauze.yanglib.model.Module;
import com.netgauze.yanglib.model.ModuleSet;
import com.netgauze.yanglib.model.YangLibrary;
import com.netgauze.yanglib.xml.YangLibraryXmlHandler;
import io.confluent.kafka.schemaregistry.client.SchemaMetadata;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.RestService;
import io.confluent.kafka.schemaregistry.client.rest.entities.Metadata;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.parser.YangYinParser;
import org.yangcentral.yangkit.register.YangStatementImplRegister;

/**
 * Builds an RFC 8525 YANG Library disk cache from Confluent Schema Registry data and returns a
 * {@link YangSchemaContext} loaded via {@link YangYinParser#parseFromYangLibrary(File, File)}.
 *
 * <h3>Disk layout (mirrors NetGauze's local cache)</h3>
 *
 * <pre>
 * {@code <cacheRoot>/
 *   schema-id-42/
 *     yang-lib.xml       <- RFC 8525 XML generated from SR data
 *     modules/
 *       ietf-interfaces@2018-02-20.yang
 *       cisco-xe-deviation.yang
 *       ...}
 * </pre>
 *
 * <h3>Deviation detection</h3>
 *
 * Each YANG module text is scanned for {@code deviation} statements. The deviated module name is
 * extracted from the XPath prefix (e.g. {@code deviation "/ietf-interfaces:x" → "ietf-interfaces"})
 * and stored in the {@code <deviation>} element of the generated {@code yang-lib.xml}. This ensures
 * {@link org.yangcentral.yangkit.parser.YangLibraryParser} applies vendor deviations during schema
 * context construction.
 *
 * <h3>Feature detection</h3>
 *
 * NetGauze stores enabled features in Confluent SR {@code Metadata.tags["features"]}. These are
 * read per-module and written into the {@code <feature>} elements of the XML.
 *
 * <h3>Cache behaviour</h3>
 *
 * <ul>
 *   <li>On first access per schema-id the cache is built from SR (HTTP calls).
 *   <li>On subsequent calls within the same JVM, an in-memory {@link YangSchemaContext} is returned
 *       directly (no disk or SR access).
 *   <li>If the JVM restarts but the disk cache dir already exists, the XML+files are reused without
 *       contacting SR again.
 * </ul>
 */
public class YangLibraryCacheBuilder {

  private static final Logger log = LoggerFactory.getLogger(YangLibraryCacheBuilder.class);

  // Regex patterns for extracting metadata from raw YANG text
  private static final Pattern NAMESPACE_PATTERN = Pattern.compile("\\bnamespace\\s+\"([^\"]+)\"");
  private static final Pattern REVISION_PATTERN =
      Pattern.compile("\\brevision\\s+\"(\\d{4}-\\d{2}-\\d{2})\"");
  // deviation "/prefix:something" or deviation /prefix:something
  private static final Pattern DEVIATION_TARGET_PATTERN =
      Pattern.compile("\\bdeviation\\s+[\"']?/([^:/\"'\\s]+):");
  // import <module-name> { ... prefix <p>; ... } — used to resolve deviation prefixes
  private static final Pattern IMPORT_BLOCK_PATTERN =
      Pattern.compile("\\bimport\\s+([\\w-]+)\\s*\\{([^}]*)", Pattern.DOTALL);
  private static final Pattern IMPORT_PREFIX_PATTERN =
      Pattern.compile("\\bprefix\\s+[\"']?([\\w.-]+)[\"']?\\s*;");
  // top-level feature statement: feature <name> { or feature <name>;
  private static final Pattern FEATURE_DEF_PATTERN =
      Pattern.compile("\\bfeature\\s+([\\w.-]+)\\s*[{;]");
  // Matches 'module foo {' or 'submodule foo {' to extract module name from raw YANG text
  private static final Pattern MODULE_NAME_PATTERN =
      Pattern.compile("\\b(?:module|submodule)\\s+([\\S]+)\\s*\\{");

  // In-memory context cache – avoids re-parsing on every message (static = shared across instances)
  private static final Map<Integer, YangSchemaContext> contextCache = new ConcurrentHashMap<>();
  // Schema-ids that permanently failed to build (static = shared across instances)
  private static final Set<Integer> failedSchemaIds =
      Collections.newSetFromMap(new ConcurrentHashMap<>());
  // In-progress async schema builds — ensures only one build per schema-id across all instances
  private static final ConcurrentHashMap<Integer, CompletableFuture<YangSchemaContext>>
      pendingBuilds = new ConcurrentHashMap<>();
  // Single background thread for schema building — keeps parse lock contention zero
  private static final ExecutorService BUILD_EXECUTOR =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "yang-schema-builder");
            t.setDaemon(true);
            return t;
          });

  // Cross-classloader parse lock stored in System.getProperties() — the only VM-global
  // singleton that survives across isolated plugin classloaders (Confluent SR ServiceLoader).
  // YangYinParser and ctx.validate() share static mutable state and are NOT thread-safe.
  private static final String PARSE_LOCK_KEY = "yangkit.global.parse.lock";

  private static Object getParseLock() {
    synchronized (System.getProperties()) {
      Object lock = System.getProperties().get(PARSE_LOCK_KEY);
      if (lock == null) {
        lock = new Object();
        System.getProperties().put(PARSE_LOCK_KEY, lock);
      }
      return lock;
    }
  }

  private final SchemaRegistryClient schemaRegistry;
  private final File cacheRoot;

  public YangLibraryCacheBuilder(SchemaRegistryClient schemaRegistry, File cacheRoot) {
    this.schemaRegistry = schemaRegistry;
    this.cacheRoot = cacheRoot;
    YangStatementImplRegister.registerImpl();
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Returns a {@link YangSchemaContext} for the given schema ID, building and caching it on first
   * access.
   *
   * @param schemaId the integer ID from the Kafka {@code schema-id} header
   * @param subject the Confluent SR subject name for the root module
   * @return validated {@link YangSchemaContext} with deviations and features applied
   */
  public YangSchemaContext getOrBuild(int schemaId, String subject) {
    // Fast path: already cached — no blocking, instant return
    YangSchemaContext cached = contextCache.get(schemaId);
    if (cached != null) return cached;
    if (failedSchemaIds.contains(schemaId)) {
      log.warn("[Cache] Skipping schema-id={} (previously failed to build)", schemaId);
      return null;
    }
    // Start async build if not already in progress (computeIfAbsent is atomic — only one build
    // per schema-id is ever started, even under concurrent consumer threads)
    pendingBuilds.computeIfAbsent(
        schemaId,
        id -> {
          log.info(
              "[Cache] Starting async schema build for schema-id={} — records processed without"
                  + " YANG context until schema is ready",
              id);
          CompletableFuture<YangSchemaContext> future = new CompletableFuture<>();
          BUILD_EXECUTOR.submit(
              () -> {
                try {
                  long start = System.currentTimeMillis();
                  YangSchemaContext ctx = buildAndCache(id, subject);
                  long elapsed = System.currentTimeMillis() - start;
                  log.info("[Cache] Async schema build complete for schema-id={} in {}ms", id, elapsed);
                  future.complete(ctx);
                } catch (Exception e) {
                  failedSchemaIds.add(id);
                  log.error("[Cache] Async schema build failed for schema-id={}: {}", id, e.getMessage(), e);
                  future.completeExceptionally(e);
                } finally {
                  pendingBuilds.remove(id);
                }
              });
          return future;
        });
    // Do NOT block the consumer thread — return null so Kafka keeps polling.
    // The deserializer handles null context as warn-and-continue (no crash, no rebalance).
    // Once the background build finishes, the next getOrBuild() call hits the cache fast path.
    log.warn("[Cache] Schema not yet ready for schema-id={} — record deserialized without YANG context (building in background)", schemaId);
    return null;
  }

  private YangSchemaContext buildAndCache(int schemaId, String subject) throws Exception {

    // Build yang-lib.xml + modules/*.yang from SR, then parse via YangYinParser.
    // This applies device-specific features (from SR metadata tags) and deviations
    // (detected by scanning YANG texts for 'deviation' statements), giving us the
    // correct device-specific schema context for validation.
    File schemaDir = new File(cacheRoot, "schema-id-" + schemaId);
    File yangLibXml = new File(schemaDir, "yang-lib.xml");
    File modulesDir = new File(schemaDir, "modules");

    String[] existingModules = modulesDir.list();
    if (!yangLibXml.exists() || existingModules == null || existingModules.length == 0) {
      schemaDir.mkdirs();
      modulesDir.mkdirs();
      log.info("Fetching YANG modules for schema-id={} from Schema Registry", schemaId);
      buildFromSchemaRegistry(schemaId, subject, yangLibXml, modulesDir);
    }

    YangSchemaContext ctx;
    log.debug("[Cache] Queuing parse for schema-id={}", schemaId);
    // Global parse lock: YangYinParser has shared static state, must be single-threaded.
    // ctx.validate() runs OUTSIDE this lock so other schema-ids can parse concurrently
    // once parsing is done.
    synchronized (getParseLock()) {
      log.info("[Cache] Parsing yang-lib.xml for schema-id={}", schemaId);
      ctx = YangYinParser.parseFromYangLibrary(yangLibXml, modulesDir);
      log.info("[Cache] Parse done for schema-id={}", schemaId);
    }
    // Run ctx.validate() on a background thread with a 120s timeout.
    // validate() resolves belongs-to links, imports, augments, and XPath/leafref checks.
    // If it times out, the partially-initialized context is still used — unknown nodes
    // are handled as UNKNOWN_ELEMENT (warn-and-continue) in the deserializer.
    log.info("[Cache] Running validate for schema-id={}", schemaId);
    validateWithTimeout(ctx);
    log.info("[Cache] Validate done for schema-id={}", schemaId);
    contextCache.put(schemaId, ctx);
    log.info(
        "YANG Library schema loaded for schema-id={}: {} modules (features + deviations applied)",
        schemaId,
        ctx.getModules().size());
    return ctx;
  }

  // ---------------------------------------------------------------------------
  // Build yang-lib.xml + modules/*.yang from Confluent Schema Registry
  // ---------------------------------------------------------------------------

  private void buildFromSchemaRegistry(
      int schemaId, String subject, File yangLibXml, File modulesDir) throws Exception {

    // 1. Fetch all modules from SR recursively (BFS over SchemaReference chains)
    Map<String, ModuleData> allModules = fetchAllModules(schemaId, subject);

    // 2. Write each module's YANG text to modules/*.yang
    Map<String, File> moduleFiles = writeYangFiles(allModules, modulesDir);

    // 3. Detect deviation relationships by scanning YANG texts
    Map<String, List<String>> deviationMap = buildDeviationMap(allModules);

    // 4. Build yang-library-java model
    Map<String, Module> modules = new LinkedHashMap<>();
    for (Map.Entry<String, ModuleData> entry : allModules.entrySet()) {
      String name = entry.getKey();
      ModuleData data = entry.getValue();

      String revision = extractRevision(data.yangText);
      String namespace = extractNamespace(data.yangText);
      if (namespace == null) namespace = "";

      // Use union of SR-declared features and all features defined in the YANG module text.
      // Some devices advertise an incomplete feature list in their YANG Library but still send
      // data for features they actually support (e.g. ietf-yang-push 'xpath'). Enabling all
      // defined features ensures those nodes appear in the schema tree.
      Set<String> featureSet =
          new HashSet<>(data.tags.getOrDefault("features", Collections.emptyList()));
      featureSet.addAll(extractAllFeatureNames(data.yangText));
      List<String> features = new ArrayList<>(featureSet);
      List<String> deviations = deviationMap.getOrDefault(name, Collections.emptyList());
      if (!deviations.isEmpty()) {
        log.debug("[yang-lib.xml] Module '{}' will have deviation entries: {}", name, deviations);
      }

      // file:// location so YangLibraryParser finds the file directly
      String path = moduleFiles.get(name).getAbsolutePath().replace("\\", "/");
      List<String> locations = List.of("file://" + path);

      modules.put(
          name,
          new Module(
              name,
              revision,
              namespace,
              features,
              deviations,
              Collections.emptyList(),
              Collections.emptyList(),
              locations));
    }

    ModuleSet moduleSet = new ModuleSet("default", modules, new LinkedHashMap<>());
    Map<String, ModuleSet> moduleSets = new LinkedHashMap<>();
    moduleSets.put("default", moduleSet);

    YangLibrary yangLibrary =
        new YangLibrary(
            "schema-id-" + schemaId, moduleSets, new LinkedHashMap<>(), new LinkedHashMap<>());

    // 5. Serialise to RFC 8525 yang-lib.xml using yang-library-java
    String xml = YangLibraryXmlHandler.toXml(yangLibrary);
    Files.writeString(yangLibXml.toPath(), xml, StandardCharsets.UTF_8);
    log.info("Generated yang-lib.xml at {} with {} modules", yangLibXml, modules.size());
  }

  // ---------------------------------------------------------------------------
  // Schema Registry recursive fetch
  // ---------------------------------------------------------------------------

  /**
   * Fetches the root schema by ID and then recursively follows all {@link SchemaReference} chains
   * to collect every dependent YANG module.
   *
   * <p>Uses {@link RestService} directly (bypassing {@code SchemaRegistryClient.getSchemaById}) to
   * avoid triggering {@code YangSchemaProvider.parseSchemaOrElseThrow} which runs ANTLR. ANTLR
   * classes may be relocated in the shaded jar, causing {@code IfFeatureExpressionParser} to fail
   * when loaded via the SR parse path.
   */
  private Map<String, ModuleData> fetchAllModules(int schemaId, String subject) throws Exception {
    Map<String, ModuleData> result = new LinkedHashMap<>();

    RestService restService = getRestService();

    // Fetch root schema raw text by ID — no parseSchema triggered
    io.confluent.kafka.schemaregistry.client.rest.entities.SchemaString rootRaw =
        restService.getId(schemaId);
    String rootYangText = rootRaw.getSchemaString();
    String rootName = extractModuleName(rootYangText);
    log.debug("Fetched root module '{}' for schema-id={}", rootName, schemaId);
    List<SchemaReference> rootRefs =
        rootRaw.getReferences() != null ? rootRaw.getReferences() : Collections.emptyList();
    Map<String, List<String>> rootTags = fetchTagsForSubject(subject);
    result.put(rootName, new ModuleData(rootYangText, rootTags));

    // BFS over all transitive references
    java.util.Queue<SchemaReference> queue = new java.util.LinkedList<>(rootRefs);
    Set<String> visited = new HashSet<>();
    while (!queue.isEmpty()) {
      SchemaReference ref = queue.poll();
      String visitKey = ref.getSubject() + ":" + ref.getVersion();
      if (!visited.add(visitKey)) continue;
      try {
        io.confluent.kafka.schemaregistry.client.rest.entities.Schema refSchema =
            restService.getVersion(ref.getSubject(), ref.getVersion());
        String yangText = refSchema.getSchema();
        String modName = extractModuleName(yangText);
        if (!result.containsKey(modName)) {
          List<SchemaReference> refs =
              refSchema.getReferences() != null
                  ? refSchema.getReferences()
                  : Collections.emptyList();
          Map<String, List<String>> tags = fetchTagsForSubject(ref.getSubject());
          result.put(modName, new ModuleData(yangText, tags));
          queue.addAll(refs);
        }
      } catch (Exception e) {
        log.warn(
            "Could not fetch SR reference {}:{}: {}",
            ref.getSubject(),
            ref.getVersion(),
            e.getMessage());
      }
    }

    log.info("Fetched {} YANG modules from SR for schema-id={}", result.size(), schemaId);
    return result;
  }

  /** Gets the underlying RestService via reflection to bypass parseSchema/ANTLR. */
  private RestService getRestService() {
    try {
      Class<?> cls = schemaRegistry.getClass();
      while (cls != null) {
        try {
          java.lang.reflect.Field f = cls.getDeclaredField("restService");
          f.setAccessible(true);
          return (RestService) f.get(schemaRegistry);
        } catch (NoSuchFieldException e) {
          cls = cls.getSuperclass();
        }
      }
      throw new RuntimeException("restService field not found on " + schemaRegistry.getClass());
    } catch (Exception e) {
      throw new RuntimeException("Cannot access restService from SchemaRegistryClient", e);
    }
  }

  /** Fetches SR metadata tags for a subject without triggering schema parsing. */
  private Map<String, List<String>> fetchTagsForSubject(String subject) {
    try {
      SchemaMetadata meta = schemaRegistry.getLatestSchemaMetadata(subject);
      return extractTags(meta.getMetadata());
    } catch (Exception e) {
      return Collections.emptyMap();
    }
  }

  /** Extracts the module/submodule name from raw YANG text. */
  private String extractModuleName(String yangText) {
    if (yangText == null) return "unknown";
    Matcher m = MODULE_NAME_PATTERN.matcher(yangText);
    return m.find() ? m.group(1) : "unknown";
  }

  // ---------------------------------------------------------------------------
  // Write YANG files to disk
  // ---------------------------------------------------------------------------

  private Map<String, File> writeYangFiles(Map<String, ModuleData> allModules, File modulesDir)
      throws Exception {
    Map<String, File> fileMap = new LinkedHashMap<>();
    for (Map.Entry<String, ModuleData> entry : allModules.entrySet()) {
      String name = entry.getKey();
      String yangText = entry.getValue().yangText;
      String revision = extractRevision(yangText);
      String fileName =
          (revision != null && !revision.isEmpty())
              ? name + "@" + revision + ".yang"
              : name + ".yang";
      File yangFile = new File(modulesDir, fileName);
      Files.writeString(yangFile.toPath(), yangText, StandardCharsets.UTF_8);
      fileMap.put(name, yangFile);
    }
    return fileMap;
  }

  // ---------------------------------------------------------------------------
  // Deviation detection
  // ---------------------------------------------------------------------------

  /**
   * Scans all module YANG texts for {@code deviation} statements and builds a map of {@code
   * base-module-name → [deviation-module-name, ...]}.
   */
  private Map<String, List<String>> buildDeviationMap(Map<String, ModuleData> allModules) {
    Map<String, List<String>> deviationMap = new HashMap<>();
    for (Map.Entry<String, ModuleData> entry : allModules.entrySet()) {
      String devModuleName = entry.getKey();
      Set<String> deviatedModules = extractDeviatedModuleNames(entry.getValue().yangText);
      if (!deviatedModules.isEmpty()) {
        log.debug("[DeviationScan] '{}' deviates: {}", devModuleName, deviatedModules);
      }
      for (String deviatedModule : deviatedModules) {
        deviationMap.computeIfAbsent(deviatedModule, k -> new ArrayList<>()).add(devModuleName);
      }
    }
    log.debug("[DeviationScan] deviationMap (targetModule -> deviatingModules): {}", deviationMap);
    return deviationMap;
  }

  /**
   * Returns the set of module names deviated by the given YANG text.
   *
   * <p>Deviation paths use import prefixes, not module names. For example: {@code deviation
   * /if:interfaces} where {@code import ietf-interfaces { prefix if; }}. This method builds a
   * prefix-to-module-name map from the import blocks first, then resolves each captured prefix to
   * the real module name.
   */
  private Set<String> extractDeviatedModuleNames(String yangText) {
    // Step 1: build prefix -> module name map from import blocks
    Map<String, String> prefixToModule = new HashMap<>();
    Matcher importMatcher = IMPORT_BLOCK_PATTERN.matcher(yangText);
    while (importMatcher.find()) {
      String moduleName = importMatcher.group(1);
      String importBody = importMatcher.group(2);
      Matcher prefixMatcher = IMPORT_PREFIX_PATTERN.matcher(importBody);
      if (prefixMatcher.find()) {
        prefixToModule.put(prefixMatcher.group(1), moduleName);
      }
    }

    // Step 2: extract deviation prefixes and resolve to real module names
    Set<String> result = new HashSet<>();
    Matcher m = DEVIATION_TARGET_PATTERN.matcher(yangText);
    while (m.find()) {
      String prefix = m.group(1);
      // Resolve prefix to module name; fall back to the prefix itself if not in import list
      result.add(prefixToModule.getOrDefault(prefix, prefix));
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // YANG text parsing helpers
  // ---------------------------------------------------------------------------

  private String extractNamespace(String yangText) {
    Matcher m = NAMESPACE_PATTERN.matcher(yangText);
    return m.find() ? m.group(1) : null;
  }

  private String extractRevision(String yangText) {
    Matcher m = REVISION_PATTERN.matcher(yangText);
    return m.find() ? m.group(1) : null;
  }

  /**
   * Extracts all top-level {@code feature} statement names from a YANG module text. Used to
   * supplement SR-declared features so that if-feature guarded nodes are included in the schema
   * tree even when the device's YANG Library advertisement is incomplete.
   */
  private Set<String> extractAllFeatureNames(String yangText) {
    Set<String> features = new HashSet<>();
    Matcher m = FEATURE_DEF_PATTERN.matcher(yangText);
    while (m.find()) {
      features.add(m.group(1));
    }
    return features;
  }

  // ---------------------------------------------------------------------------
  // SR Metadata helpers
  // ---------------------------------------------------------------------------

  /** Internal data holder */
  private static final class ModuleData {
    final String yangText;
    final Map<String, List<String>> tags;

    ModuleData(String yangText, Map<String, List<String>> tags) {
      this.yangText = yangText;
      this.tags = tags;
    }
  }

  /**
   * Runs only init+build per module — skips the expensive module.validate()/afterValidate()
   * XPath/leafref checks. This is sufficient to expand all groupings/augments needed for data
   * parsing. Called on the BUILD_EXECUTOR daemon thread — no timeout needed.
   */
  private void validateWithTimeout(YangSchemaContext ctx) {
    try {
      List<org.yangcentral.yangkit.model.api.stmt.Module> modules = new ArrayList<>();
      modules.addAll(ctx.getModules());
      modules.addAll(ctx.getImportOnlyModules());
      ctx.buildDependencies();
      // init — initialises each module's fields and sub-statements
      for (org.yangcentral.yangkit.model.api.stmt.Module module : modules) {
        if (module.getContext() == null) {
          module.setContext(new org.yangcentral.yangkit.base.YangContext(ctx, module));
        }
        module.init();
      }
      // build — expands groupings/uses, resolves augments, builds schema tree
      for (org.yangcentral.yangkit.model.api.stmt.Module module : modules) {
        module.build();
      }
      log.info("[Cache] buildOnly() completed successfully for {} modules", ctx.getModules().size());
    } catch (Exception e) {
      log.warn("[Cache] buildOnly() threw: {} — continuing with partial context.", e.getMessage());
    }
  }

  private Map<String, List<String>> extractTags(Metadata metadata) {
    if (metadata == null || metadata.getTags() == null) {
      return Collections.emptyMap();
    }
    // Metadata.getTags() returns SortedMap<String, SortedSet<String>>
    Map<String, List<String>> result = new HashMap<>();
    for (Map.Entry<String, SortedSet<String>> entry : metadata.getTags().entrySet()) {
      result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
    }
    return result;
  }
}
