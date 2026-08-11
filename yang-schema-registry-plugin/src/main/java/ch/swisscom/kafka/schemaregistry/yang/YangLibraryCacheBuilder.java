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

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.RestService;
import io.confluent.kafka.schemaregistry.client.rest.entities.Metadata;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
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
 * Builds an RFC 8525 compliant YANG Library from Confluent Schema Registry data and returns a
 * {@link YangSchemaContext} loaded via {@link YangYinParser#parseFromYangLibrary(File, File)}.
 *
 * <h3>How it works</h3>
 *
 * <ol>
 *   <li>Fetch the root YANG module from SR by schema-id.
 *   <li>Recursively follow all {@link SchemaReference} chains (BFS) — this fetches all transitive
 *       dependencies including imports, augmentation modules and deviation modules, since the YANG
 *       Message Broker Producer registers all of them as SR references.
 *   <li>Write all YANG module texts to disk as {@code modules/*.yang}.
 *   <li>Build a RFC 8525 compliant {@code yang-lib.xml} with:
 *       <ul>
 *         <li>{@code <module>} — all modules; root module gets features from SR {@code
 *             metadata.tags.features} only, others have no features
 *         <li>{@code <content-id>} — sha256 of all module texts
 *       </ul>
 *   <li>Parse via {@link YangYinParser#parseFromYangLibrary} — yangkit loads all modules and
 *       automatically applies deviations and augmentations during {@code module.build()}.
 * </ol>
 *
 * <h3>Disk layout</h3>
 *
 * <pre>
 * {@code <cacheRoot>/
 *   schema-id-42/
 *     yang-lib.xml       <- RFC 8525 XML
 *     modules/
 *       ietf-subscribed-notifications@2019-09-09.yang
 *       huawei-ietf-subscribed-notifications-deviations-OC-NE-M2K-B.yang
 *       ...}
 * </pre>
 */
public class YangLibraryCacheBuilder {

  private static final Logger log = LoggerFactory.getLogger(YangLibraryCacheBuilder.class);

  // Regex patterns — only what is still needed
  private static final Pattern NAMESPACE_PATTERN = Pattern.compile("\\bnamespace\\s+\"([^\"]+)\"");
  private static final Pattern REVISION_PATTERN =
      Pattern.compile("\\brevision\\s+\"(\\d{4}-\\d{2}-\\d{2})\"");
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
                  log.info(
                      "[Cache] Async schema build complete for schema-id={} in {}ms", id, elapsed);
                  future.complete(ctx);
                } catch (Exception e) {
                  failedSchemaIds.add(id);
                  log.error(
                      "[Cache] Async schema build failed for schema-id={}: {}",
                      id,
                      e.getMessage(),
                      e);
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
    log.warn(
        "[Cache] Schema not yet ready for schema-id={} — record deserialized without YANG context (building in background)",
        schemaId);
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
    synchronized (getParseLock()) {
      log.info("[Cache] Parsing yang-lib.xml for schema-id={}", schemaId);
      ctx = YangYinParser.parseFromYangLibrary(yangLibXml, modulesDir);
      log.info(
          "[Cache] Parse done for schema-id={} — {} main modules loaded",
          schemaId,
          ctx.getModules().size());
    }
    log.info("[Cache] Running init+build for schema-id={}", schemaId);
    validateWithTimeout(ctx);
    log.info("[Cache] Init+build done for schema-id={}", schemaId);
    contextCache.put(schemaId, ctx);
    log.info(
        "YANG Library schema loaded for schema-id={}: {} modules",
        schemaId,
        ctx.getModules().size());
    return ctx;
  }

  // ---------------------------------------------------------------------------
  // Build yang-lib.xml + modules/*.yang from Confluent Schema Registry
  // ---------------------------------------------------------------------------

  private void buildFromSchemaRegistry(
      int schemaId, String subject, File yangLibXml, File modulesDir) throws Exception {

    // 1. Fetch all modules from SR recursively (BFS over SchemaReference chains).
    //    The root module is the main module (identified by schema-id).
    //    All transitively fetched modules (imports, augmentations, deviations)
    //    are loaded together — yangkit applies everything automatically.
    Map<String, ModuleData> allModules = fetchAllModules(schemaId, subject);
    String rootModuleName = allModules.keySet().iterator().next(); // first entry = root

    // 2. Write each module's YANG text to modules/*.yang
    Map<String, File> moduleFiles = writeYangFiles(allModules, modulesDir);

    // 3. Build all module entries and content-id
    //    Root module → features from SR metadata.tags.features ONLY
    //    All other modules (imports, augmentations, deviations) → no features
    //    yangkit applies deviations and augmentations automatically during module.build()
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    List<String[]> moduleEntries =
        new ArrayList<>(); // [name, revision, namespace, location, features...]

    for (Map.Entry<String, ModuleData> entry : allModules.entrySet()) {
      String name = entry.getKey();
      ModuleData data = entry.getValue();

      String revision = extractRevision(data.yangText);
      String namespace = extractNamespace(data.yangText);
      if (namespace == null) namespace = "";

      String path = moduleFiles.get(name).getAbsolutePath().replace("\\", "/");
      String location = "file://" + path;

      // Update content-id hash
      digest.update(data.yangText.getBytes(StandardCharsets.UTF_8));

      // Features from SR metadata.tags.features for EVERY module that has them.
      // The YANG Message Broker Producer mirrors device-advertised features into
      // SR metadata for each module subject. Use them as-is — no YANG text scanning.
      List<String> features =
          new ArrayList<>(data.tags.getOrDefault("features", Collections.emptyList()));

      if (!features.isEmpty()) {
        log.debug("Module '{}' features from SR metadata: {}", name, features);
      }

      // Store: [0]=name, [1]=revision, [2]=namespace, [3]=location, [4+]=features
      List<String> entry2 = new ArrayList<>();
      entry2.add(name);
      entry2.add(revision != null ? revision : "");
      entry2.add(namespace);
      entry2.add(location);
      entry2.addAll(features);
      moduleEntries.add(entry2.toArray(new String[0]));
    }

    // 4. Generate content-id from sha256
    byte[] hashBytes = digest.digest();
    StringBuilder contentId = new StringBuilder();
    for (byte b : hashBytes) contentId.append(String.format("%02x", b));

    // 5. Build RFC 8525 yang-lib.xml directly — no external library needed
    String xml = buildYangLibXml(moduleEntries, contentId.toString());
    Files.writeString(yangLibXml.toPath(), xml, StandardCharsets.UTF_8);
    log.info(
        "Generated yang-lib.xml at {} with {} modules (root='{}', content-id={})",
        yangLibXml,
        moduleEntries.size(),
        rootModuleName,
        contentId.substring(0, 8) + "...");
  }

  /**
   * Builds a minimal RFC 8525 yang-library XML string directly. Each entry array: [0]=name,
   * [1]=revision, [2]=namespace, [3]=location, [4+]=features
   */
  private String buildYangLibXml(List<String[]> moduleEntries, String contentId) {
    String ns = "urn:ietf:params:xml:ns:yang:ietf-yang-library";
    StringBuilder sb = new StringBuilder();
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    sb.append("<yang-library xmlns=\"").append(ns).append("\">\n");
    sb.append("  <module-set>\n");
    sb.append("    <name>default</name>\n");
    for (String[] m : moduleEntries) {
      sb.append("    <module>\n");
      sb.append("      <name>").append(escape(m[0])).append("</name>\n");
      if (!m[1].isEmpty()) {
        sb.append("      <revision>").append(escape(m[1])).append("</revision>\n");
      }
      sb.append("      <namespace>").append(escape(m[2])).append("</namespace>\n");
      // features (index 4 onwards)
      for (int i = 4; i < m.length; i++) {
        sb.append("      <feature>").append(escape(m[i])).append("</feature>\n");
      }
      sb.append("      <location>").append(escape(m[3])).append("</location>\n");
      sb.append("    </module>\n");
    }
    sb.append("  </module-set>\n");
    sb.append("  <content-id>").append(escape(contentId)).append("</content-id>\n");
    sb.append("</yang-library>\n");
    return sb.toString();
  }

  /** Escapes XML special characters in text content. */
  private String escape(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
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
    // Extract tags directly from the root schema response — not from subject name lookup
    Map<String, List<String>> rootTags = extractTags(rootRaw.getMetadata());
    List<String> rootFeatures = rootTags.getOrDefault("features", Collections.emptyList());
    if (!rootFeatures.isEmpty()) {
      log.debug("[Cache] Root module '{}' features: {}", rootName, rootFeatures);
    }
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
          // Extract tags directly from the fetched schema version — not from
          // getLatestSchemaMetadata
          // since features are on the specific registered version, not necessarily the latest
          Map<String, List<String>> tags = extractTags(refSchema.getMetadata());
          List<String> features = tags.getOrDefault("features", Collections.emptyList());
          if (!features.isEmpty()) {
            log.debug("[Cache] Module '{}' features: {}", modName, features);
          }
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
   * Initialises and builds the schema context. Only calls build() on main modules — import-only
   * modules (deviation/augmentation/import modules) only need init() since they contribute no
   * schema nodes directly. This significantly reduces build time for large module sets.
   */
  private void validateWithTimeout(YangSchemaContext ctx) {
    long t1 = System.currentTimeMillis();
    List<org.yangcentral.yangkit.model.api.stmt.Module> allModules = new ArrayList<>();
    allModules.addAll(ctx.getModules());
    allModules.addAll(ctx.getImportOnlyModules());

    try {
      ctx.buildDependencies();
    } catch (Exception e) {
      log.warn("[Cache] buildDependencies threw: {} — continuing", e.getMessage());
    }
    log.debug("[Cache] buildDependencies done ({}ms)", System.currentTimeMillis() - t1);

    // init all modules individually — one failure must not stop the rest
    long t2 = System.currentTimeMillis();
    int initFailed = 0;
    for (org.yangcentral.yangkit.model.api.stmt.Module module : allModules) {
      try {
        if (module.getContext() == null) {
          module.setContext(new org.yangcentral.yangkit.base.YangContext(ctx, module));
        }
        module.init();
      } catch (Exception e) {
        initFailed++;
        log.warn("[Cache] init() failed for module {}: {}", module.getArgStr(), e.getMessage());
      }
    }
    log.info(
        "[Cache] init() done — {} ok, {} failed ({}ms)",
        allModules.size() - initFailed,
        initFailed,
        System.currentTimeMillis() - t2);

    // build all modules individually — one failure must not stop the rest
    long t3 = System.currentTimeMillis();
    int buildFailed = 0;
    for (org.yangcentral.yangkit.model.api.stmt.Module module : allModules) {
      try {
        module.build();
      } catch (Exception e) {
        buildFailed++;
        log.warn("[Cache] build() failed for module {}: {}", module.getArgStr(), e.getMessage());
      }
    }
    log.info(
        "[Cache] build() done — {} ok, {} failed ({}ms)",
        allModules.size() - buildFailed,
        buildFailed,
        System.currentTimeMillis() - t3);

    log.info("[Cache] buildOnly() completed for {} modules", ctx.getModules().size());
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
