package ch.swisscom.kafka.yanglibrary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider;
import ch.swisscom.kafka.serializers.yang.json.KafkaYangJsonSchemaDeserializer;
import ch.swisscom.kafka.serializers.yang.json.KafkaYangJsonSchemaDeserializerConfig;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.RestService;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaString;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.register.YangStatementImplRegister;

class YangLibraryCacheBuilderTest {

  private static final long RETRY_MILLIS = Duration.ofSeconds(30).toMillis();
  private static final AtomicInteger NEXT_SCHEMA_ID = new AtomicInteger(700_000);

  @TempDir Path cacheRoot;

  @Test
  void parseYangHeaderReadsStatementsInsteadOfCommentsAndDescriptions() throws Exception {
    YangStatementImplRegister.registerImpl();
    String yang =
        "module sample {\n"
            + "  yang-version 1.1;\n"
            + "  // namespace \"urn:example:comment\";\n"
            + "  description \"namespace \\\"urn:example:description\\\";\";\n"
            + "  namespace 'urn:example:actual';\n"
            + "  prefix sample;\n"
            + "  revision 2023-01-01;\n"
            + "  revision 2024-01-15 { description \"current\"; }\n"
            + "}\n";

    YangLibraryCacheBuilder.YangModuleHeader header = YangLibraryCacheBuilder.parseYangHeader(yang);

    assertEquals("sample", header.name());
    assertEquals("2024-01-15", header.revision());
    assertEquals("urn:example:actual", header.namespace());
  }

  @Test
  void yangLibraryModeIsDisabledByDefault() {
    KafkaYangJsonSchemaDeserializerConfig config =
        new KafkaYangJsonSchemaDeserializerConfig(
            Map.of("schema.registry.url", "http://localhost:8081"));

    assertEquals(
        "", config.getString(KafkaYangJsonSchemaDeserializerConfig.YANG_LIBRARY_CACHE_PATH));
  }

  @Test
  void configuredCachePathThatIsAFileFailsConfiguration() throws Exception {
    Path invalidPath = Files.createFile(cacheRoot.resolve("not-a-directory"));
    Map<String, Object> properties = new HashMap<>();
    properties.put("schema.registry.url", "bogus");
    properties.put(
        KafkaYangJsonSchemaDeserializerConfig.YANG_LIBRARY_CACHE_PATH, invalidPath.toString());
    KafkaYangJsonSchemaDeserializer<?> deserializer =
        new KafkaYangJsonSchemaDeserializer<>(
            new MockSchemaRegistryClient(Collections.singletonList(new YangSchemaProvider())));

    assertThrows(ConfigException.class, () -> deserializer.configure(properties, true));
  }

  @Test
  void schemaIsUnavailableUntilTheAsyncBuildFinishesAndConcurrentCallsShareOneBuild()
      throws Exception {
    StubRestService restService = successfulService();
    QueuedExecutor buildExecutor = new QueuedExecutor();
    YangLibraryCacheBuilder builder = newBuilder(restService, buildExecutor, new AtomicLong(1000));
    int schemaId = NEXT_SCHEMA_ID.getAndIncrement();

    ExecutorService callers = Executors.newFixedThreadPool(16);
    CountDownLatch ready = new CountDownLatch(16);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<YangSchemaContext>> results = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        results.add(
            callers.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return builder.getOrBuild(schemaId, "root");
                }));
      }
      assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
      start.countDown();
      for (Future<YangSchemaContext> result : results) {
        assertNull(result.get());
      }
    } finally {
      callers.shutdownNow();
    }

    assertEquals(1, buildExecutor.size());
    buildExecutor.runNext();
    assertNotNull(builder.getOrBuild(schemaId, "root"));
    assertEquals(1, restService.rootCalls.get());
  }

  @Test
  void recursivelyFetchesReferencesAndReusesTheDiskCache() throws Exception {
    StubRestService restService = successfulService();
    QueuedExecutor buildExecutor = new QueuedExecutor();
    AtomicLong clock = new AtomicLong(1000);
    YangLibraryCacheBuilder builder = newBuilder(restService, buildExecutor, clock);
    int schemaId = NEXT_SCHEMA_ID.getAndIncrement();

    assertNull(builder.getOrBuild(schemaId, "root"));
    buildExecutor.runNext();
    assertNotNull(builder.getOrBuild(schemaId, "root"));

    Path schemaDirectory = cacheRoot.resolve("schema-id-" + schemaId);
    Path libraryFile = schemaDirectory.resolve("yang-lib.xml");
    String libraryXml = Files.readString(libraryFile);
    assertTrue(libraryXml.contains("<name>root</name>"));
    assertTrue(libraryXml.contains("<name>dependency</name>"));
    assertTrue(libraryXml.contains("<name>leaf</name>"));
    assertTrue(Files.exists(schemaDirectory.resolve("modules/root@2024-01-01.yang")));
    assertTrue(Files.exists(schemaDirectory.resolve("modules/dependency@2024-01-01.yang")));
    assertTrue(Files.exists(schemaDirectory.resolve("modules/leaf@2024-01-01.yang")));
    assertEquals(1, restService.rootCalls.get());
    assertEquals(2, restService.versionCalls.get());

    StubRestService unavailableService = new StubRestService();
    YangLibraryCacheBuilder cacheReader =
        newBuilder(unavailableService, new QueuedExecutor(), clock);
    assertNotNull(cacheReader.buildAndCache(schemaId, "root"));
    assertEquals(0, unavailableService.rootCalls.get());
    assertEquals(0, unavailableService.versionCalls.get());
  }

  @Test
  void failedReferenceIsNotPersistedAndRetriesAfterCooldown() throws Exception {
    StubRestService restService = successfulService();
    restService.referenceFailure = FailureMode.NOT_FOUND;
    QueuedExecutor buildExecutor = new QueuedExecutor();
    AtomicLong clock = new AtomicLong(1000);
    YangLibraryCacheBuilder builder = newBuilder(restService, buildExecutor, clock);
    int schemaId = NEXT_SCHEMA_ID.getAndIncrement();
    Path schemaDirectory = cacheRoot.resolve("schema-id-" + schemaId);

    assertNull(builder.getOrBuild(schemaId, "root"));
    buildExecutor.runNext();
    assertFalse(Files.exists(schemaDirectory));
    assertNull(builder.getOrBuild(schemaId, "root"));
    assertEquals(0, buildExecutor.size());
    assertEquals(1, restService.rootCalls.get());
    assertEquals(1, restService.versionCalls.get());

    clock.addAndGet(RETRY_MILLIS + 1);
    restService.referenceFailure = null;
    assertNull(builder.getOrBuild(schemaId, "root"));
    buildExecutor.runNext();
    assertNotNull(builder.getOrBuild(schemaId, "root"));
    assertTrue(Files.exists(schemaDirectory.resolve("yang-lib.xml")));
    assertEquals(2, restService.rootCalls.get());
    assertEquals(3, restService.versionCalls.get());
  }

  @Test
  void failedRootLookupDoesNotCreateCacheFiles() throws Exception {
    StubRestService restService = successfulService();
    restService.rootFailure = FailureMode.SERVER_ERROR;
    QueuedExecutor buildExecutor = new QueuedExecutor();
    YangLibraryCacheBuilder builder = newBuilder(restService, buildExecutor, new AtomicLong(1000));
    int schemaId = NEXT_SCHEMA_ID.getAndIncrement();

    assertNull(builder.getOrBuild(schemaId, "root"));
    buildExecutor.runNext();

    assertFalse(Files.exists(cacheRoot.resolve("schema-id-" + schemaId)));
    assertNull(builder.getOrBuild(schemaId, "root"));
    assertEquals(0, buildExecutor.size());
    assertEquals(1, restService.rootCalls.get());
  }

  @Test
  void rootAndReferenceErrorsAndTimeoutsNeverPersistPartialCaches() throws Exception {
    for (boolean rootFailure : List.of(true, false)) {
      for (FailureMode failureMode : FailureMode.values()) {
        StubRestService restService = successfulService();
        if (rootFailure) {
          restService.rootFailure = failureMode;
        } else {
          restService.referenceFailure = failureMode;
        }
        QueuedExecutor buildExecutor = new QueuedExecutor();
        YangLibraryCacheBuilder builder =
            newBuilder(restService, buildExecutor, new AtomicLong(1000));
        int schemaId = NEXT_SCHEMA_ID.getAndIncrement();

        assertNull(builder.getOrBuild(schemaId, "root"));
        buildExecutor.runNext();

        assertFalse(Files.exists(cacheRoot.resolve("schema-id-" + schemaId)));
        assertNull(builder.getOrBuild(schemaId, "root"));
        assertEquals(0, buildExecutor.size());
        assertEquals(1, restService.rootCalls.get());
        assertEquals(rootFailure ? 0 : 1, restService.versionCalls.get());
      }
    }
  }

  private YangLibraryCacheBuilder newBuilder(
      StubRestService restService, QueuedExecutor executor, AtomicLong clock) {
    return new YangLibraryCacheBuilder(
        null, cacheRoot.toFile(), restService, executor, clock::get, RETRY_MILLIS);
  }

  private StubRestService successfulService() {
    StubRestService restService = new StubRestService();
    SchemaString root = new SchemaString(rootYang());
    root.setReferences(List.of(new SchemaReference("dependency", "dependency", 1)));
    restService.root = root;
    restService.versions.put(
        "dependency:1",
        schemaResponse(
            "dependency", 1, 2, dependencyYang(), List.of(new SchemaReference("leaf", "leaf", 1))));
    restService.versions.put("leaf:1", schemaResponse("leaf", 1, 3, leafYang(), List.of()));
    return restService;
  }

  private static Schema schemaResponse(
      String subject, int version, int id, String yang, List<SchemaReference> references) {
    SchemaString schemaString = new SchemaString(yang);
    schemaString.setReferences(references);
    return new Schema(subject, version, id, schemaString);
  }

  private static String rootYang() {
    return "module root { yang-version 1.1; namespace \"urn:root\"; prefix root; "
        + "revision 2024-01-01; import dependency { prefix dep; revision-date 2024-01-01; } "
        + "container data { leaf name { type string; } } }";
  }

  private static String dependencyYang() {
    return "module dependency { yang-version 1.1; namespace \"urn:dependency\"; prefix dep; "
        + "revision 2024-01-01; import leaf { prefix leaf; revision-date 2024-01-01; } "
        + "container dependency-data { leaf value { type leaf:value-type; } } }";
  }

  private static String leafYang() {
    return "module leaf { yang-version 1.1; namespace \"urn:leaf\"; prefix leaf; "
        + "revision 2024-01-01; typedef value-type { type string; } }";
  }

  private enum FailureMode {
    SERVER_ERROR,
    NOT_FOUND,
    TIMEOUT
  }

  private static final class QueuedExecutor implements java.util.concurrent.Executor {
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    int size() {
      return tasks.size();
    }

    void runNext() {
      Objects.requireNonNull(tasks.poll()).run();
    }
  }

  private static final class StubRestService extends RestService {
    private final AtomicInteger rootCalls = new AtomicInteger();
    private final AtomicInteger versionCalls = new AtomicInteger();
    private final Map<String, Schema> versions = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile SchemaString root;
    private volatile FailureMode rootFailure;
    private volatile FailureMode referenceFailure;

    StubRestService() {
      super("http://localhost:8081");
    }

    @Override
    public SchemaString getId(int id) throws IOException, RestClientException {
      rootCalls.incrementAndGet();
      if (rootFailure != null) {
        throwFailure(rootFailure);
      }
      return root;
    }

    @Override
    public Schema getVersion(String subject, int version) throws IOException, RestClientException {
      versionCalls.incrementAndGet();
      if (referenceFailure != null) {
        throwFailure(referenceFailure);
      }
      Schema schema = versions.get(subject + ":" + version);
      if (schema == null) {
        throw new RestClientException("reference not found", 404, 404);
      }
      return schema;
    }

    private static void throwFailure(FailureMode failureMode)
        throws IOException, RestClientException {
      if (failureMode == FailureMode.TIMEOUT) {
        throw new IOException("registry request timed out");
      }
      int status = failureMode == FailureMode.SERVER_ERROR ? 500 : 404;
      throw new RestClientException("registry request failed", status, status);
    }
  }
}
