package ch.swisscom.kafka.examples;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.yangcentral.yangkit.data.api.model.YangDataDocument;

/**
 * Example: consuming NetGauze YANG-Push Kafka messages using yang-kafka-integration.
 *
 * <p>Demonstrates two deserialization approaches:
 *
 * <ul>
 *   <li><b>Approach A</b> – Legacy path via Confluent Schema Registry. {@code
 *       KafkaYangJsonSchemaDeserializer} fetches YANG schemas from SR at runtime.
 *   <li><b>Approach B</b> – YANG Library path via local disk cache. Enabled by setting {@code
 *       yang.library.cache.path}. The deserializer builds a {@link
 *       org.yangcentral.yangkit.model.api.schema.YangSchemaContext} from an RFC 8525 {@code
 *       yang-lib.xml} on disk — no SR call needed at consumer time.
 * </ul>
 *
 * <p>NetGauze Kafka message format:
 *
 * <pre>
 *   Header "schema-id"    = UTF-8 decimal string, e.g. "42"
 *   Header "content-type" = "application/yang.data+json"
 *   Payload               = raw RFC 7951 YANG JSON (no Confluent 5-byte magic prefix)
 * </pre>
 *
 * <p>NetGauze local disk cache layout (used by Approach B):
 *
 * <pre>
 *   /bp2/data/yang-cache/
 *     schema-id-42/
 *       yang-lib.xml        RFC 8525 YANG Library XML
 *       modules/
 *         ietf-interfaces@2018-02-20.yang
 *         ...
 * </pre>
 *
 * <p>The yangkit-specific logic (parsing {@code yang-lib.xml} into a {@link
 * org.yangcentral.yangkit.model.api.schema.YangSchemaContext}) is handled internally by {@code
 * YangLibraryCacheBuilder} in yang-schema-registry-plugin and is demonstrated standalone in {@code
 * App5YangLibrary} in the yangkit repo.
 */
public class NetGauzeYangKafkaConsumerExample {

  private static final String BOOTSTRAP_SERVERS = "localhost:9092";
  private static final String TOPIC = "yang-push-telemetry";
  private static final String GROUP_ID = "yangkit-consumer-group";
  private static final String SCHEMA_REGISTRY_URL = "http://localhost:8081";

  // Default cache path — set by KafkaYangJsonSchemaDeserializerConfig
  private static final String YANG_LIBRARY_CACHE = "/bp2/data/yang-cache";

  private static final String HEADER_SCHEMA_ID = "schema-id";

  // ─────────────────────────────────────────────────────────────────────────
  // Approach A — Legacy Confluent SR path
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Build consumer props for Approach A (Confluent SR, no local cache). Set {@code
   * yang.library.cache.path} to blank to disable YANG Library mode.
   */
  public static Map<String, Object> schemaRegistryConsumerProps() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID + "-sr");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
        "ch.swisscom.kafka.serializers.yang.json.KafkaYangJsonSchemaDeserializer");
    props.put("schema.registry.url", SCHEMA_REGISTRY_URL);
    props.put("yang.library.cache.path", ""); // blank = legacy SR path
    return props;
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Approach B — YANG Library local disk cache path
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Build consumer props for Approach B (YANG Library local cache). Setting {@code
   * yang.library.cache.path} to a non-blank directory enables YANG Library mode in {@code
   * KafkaYangJsonSchemaDeserializer}.
   */
  public static Map<String, Object> yangLibraryConsumerProps() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID + "-yl");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
        "ch.swisscom.kafka.serializers.yang.json.KafkaYangJsonSchemaDeserializer");
    props.put("schema.registry.url", SCHEMA_REGISTRY_URL);
    // Non-blank path activates YANG Library mode
    props.put("yang.library.cache.path", YANG_LIBRARY_CACHE);
    return props;
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Consumer loop — same for both approaches, deserializer handles routing
  // ─────────────────────────────────────────────────────────────────────────

  public static void main(String[] args) throws Exception {
    // Switch between approaches by choosing props
    boolean useYangLibrary = args.length > 0 && args[0].equals("--yang-library");
    Map<String, Object> props =
        useYangLibrary ? yangLibraryConsumerProps() : schemaRegistryConsumerProps();

    System.out.printf(
        "Starting consumer in %s mode%n",
        useYangLibrary ? "YANG Library (local cache)" : "Schema Registry");

    try (KafkaConsumer<String, YangDataDocument> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(Collections.singletonList(TOPIC));

      while (true) {
        ConsumerRecords<String, YangDataDocument> records = consumer.poll(Duration.ofMillis(500));

        for (ConsumerRecord<String, YangDataDocument> record : records) {
          YangDataDocument doc = record.value();
          if (doc == null) {
            // null returned when schema is still building or parse failed
            System.err.printf("[offset=%d] Record skipped (null document)%n", record.offset());
            continue;
          }
          handleDocument(doc, record);
        }
      }
    }
  }

  private static void handleDocument(
      YangDataDocument doc, ConsumerRecord<String, YangDataDocument> record) {
    int children = doc.getDataChildren() == null ? 0 : doc.getDataChildren().size();
    System.out.printf(
        "[partition=%d offset=%d] YANG document: %d root node(s)%n",
        record.partition(), record.offset(), children);
    // Pass doc to application logic here
  }
}
