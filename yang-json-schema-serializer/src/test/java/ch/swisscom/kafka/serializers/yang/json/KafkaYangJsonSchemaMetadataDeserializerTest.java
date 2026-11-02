package ch.swisscom.kafka.serializers.yang.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference;
import io.confluent.kafka.schemaregistry.client.rest.entities.SubjectVersion;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.dom4j.DocumentException;
import org.junit.jupiter.api.Test;
import org.yangcentral.yangkit.common.api.validate.ValidatorResultBuilder;
import org.yangcentral.yangkit.data.api.model.YangDataDocument;
import org.yangcentral.yangkit.data.codec.json.YangDataDocumentJsonParser;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.parser.YangParserException;
import org.yangcentral.yangkit.parser.YangYinParser;

/**
 * Tests for {@link KafkaYangJsonSchemaMetadataDeserializer}, verifying that schema id, subject,
 * version and references are correctly resolved and propagated alongside the deserialized payload.
 */
public class KafkaYangJsonSchemaMetadataDeserializerTest {

  private static final int ID_SIZE = 4;

  private final Properties config;
  private final SchemaRegistryClient schemaRegistry;
  private final KafkaYangJsonSchemaSerializer serializer;
  private final KafkaYangJsonSchemaMetadataDeserializer metadataDeserializer;
  private final String topic;

  public KafkaYangJsonSchemaMetadataDeserializerTest() {
    config = new Properties();
    config.put(KafkaYangJsonSchemaSerializerConfig.AUTO_REGISTER_SCHEMAS, true);
    config.put(KafkaYangJsonSchemaSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, "bogus");
    config.put(KafkaYangJsonSchemaSerializerConfig.YANG_JSON_FAIL_INVALID_SCHEMA, true);
    config.put(KafkaYangJsonSchemaDeserializerConfig.YANG_LIBRARY_CACHE_PATH, "");

    schemaRegistry =
        new MockSchemaRegistryClient(Collections.singletonList(new YangSchemaProvider()));

    serializer = new KafkaYangJsonSchemaSerializer(schemaRegistry);
    serializer.configure(new HashMap<>(config), true);

    metadataDeserializer = new KafkaYangJsonSchemaMetadataDeserializer(schemaRegistry);
    metadataDeserializer.configure(toStringKeyedMap(config), true);

    topic = "test";
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> toStringKeyedMap(Properties props) {
    return (Map<String, ?>) (Map<?, ?>) new HashMap<>(props);
  }

  private YangDataDocument getRecord(String yang, String json) {
    YangDataDocument doc;
    ObjectMapper mapper = new ObjectMapper();
    try {
      YangSchemaContext schemaContext = YangYinParser.parse(yang);
      schemaContext.validate();
      JsonNode jsonNode = mapper.readTree(new File(json));
      doc =
          new YangDataDocumentJsonParser(schemaContext)
              .parse(jsonNode, new ValidatorResultBuilder());
    } catch (DocumentException | IOException | YangParserException e) {
      throw new RuntimeException(e);
    }
    return doc;
  }

  private Headers getDeserializationKafkaHeader(Headers serializationHeaders) {
    Headers deserializerHeaders = new RecordHeaders();
    byte[] serializedSchemaId =
        serializationHeaders
            .lastHeader(AbstractKafkaYangJsonSchemaSerializer.SCHEMA_ID_KEY)
            .value();
    int schemaId = ByteBuffer.wrap(serializedSchemaId).getInt();
    deserializerHeaders.add(
        AbstractKafkaYangJsonSchemaSerializer.SCHEMA_ID_KEY,
        ByteBuffer.allocate(ID_SIZE).putInt(schemaId).array());
    return deserializerHeaders;
  }

  @Test
  public void deserialize_schemaWithSingleModule_returnsSelfReference() {
    YangDataDocument doc =
        getRecord(
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test1/test.yang")
                .getFile(),
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test1/valid.json")
                .getFile());

    Headers serializerHeaders = new RecordHeaders();
    byte[] bytes = serializer.serialize(topic, serializerHeaders, doc);
    Headers deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);

    YangSchemaAndValue result = metadataDeserializer.deserialize(topic, deserializerHeaders, bytes);

    assertNotNull(result);
    assertNotNull(result.getValue());
    assertTrue(result.getId() > 0);
    // With isKey=true the default subject-naming strategy appends "-key" to the topic name.
    assertEquals("test-key", result.getSubject());
    assertNotNull(result.getVersion());
    assertEquals(1, result.getVersion());

    // The registered schema is a synthetic root referencing every module in its context, so a
    // schema with a single, import-free module still exposes exactly one reference: itself.
    List<SchemaReference> references = result.getReferences();
    assertEquals(1, references.size());
    assertEquals("insa-test", references.get(0).getName());
    assertNotNull(references.get(0).getVersion());
  }

  @Test
  public void deserialize_schemaWithImportedModule_returnsResolvedReferences() {
    YangDataDocument doc =
        getRecord(
            this.getClass().getClassLoader().getResource("serializer/json/test4/yangs").getFile(),
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test4/valid.json")
                .getFile());

    Headers serializerHeaders = new RecordHeaders();
    byte[] bytes = serializer.serialize(topic, serializerHeaders, doc);
    Headers deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);

    YangSchemaAndValue result = metadataDeserializer.deserialize(topic, deserializerHeaders, bytes);

    assertNotNull(result);
    assertNotNull(result.getValue());
    assertTrue(result.getId() > 0);
    assertNotNull(result.getVersion());

    // References cover every module in the schema context: the importing module and the module
    // it imports.
    List<SchemaReference> references = result.getReferences();
    assertEquals(2, references.size());
    List<String> referenceNames = references.stream().map(SchemaReference::getName).toList();
    assertTrue(referenceNames.contains("insa-test-simple-local"));
    assertTrue(referenceNames.contains("insa-test-complex-local"));
  }

  @Test
  public void deserialize_schemaRegisteredUnderDifferentTopic_returnsRegistrySubjectMetadata() {
    YangDataDocument doc =
        getRecord(
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test1/test.yang")
                .getFile(),
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test1/valid.json")
                .getFile());

    Headers serializerHeaders = new RecordHeaders();
    byte[] bytes = serializer.serialize(topic, serializerHeaders, doc);
    Headers deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);

    YangSchemaAndValue result =
        metadataDeserializer.deserialize("telemetry-yang", deserializerHeaders, bytes);

    assertNotNull(result);
    assertNotNull(result.getValue());
    assertEquals("test-key", result.getSubject());
    assertEquals(1, result.getVersion());
  }

  @Test
  public void deserialize_multipleMessagesSameSchema_cachesGetAllVersionsById() throws Exception {
    AtomicInteger callCount = new AtomicInteger();
    SchemaRegistryClient countingClient =
        new MockSchemaRegistryClient(Collections.singletonList(new YangSchemaProvider())) {
          @Override
          public synchronized Collection<SubjectVersion> getAllVersionsById(int id) {
            callCount.incrementAndGet();
            return super.getAllVersionsById(id);
          }
        };
    KafkaYangJsonSchemaSerializer localSerializer =
        new KafkaYangJsonSchemaSerializer(countingClient);
    localSerializer.configure(new HashMap<>(config), true);
    KafkaYangJsonSchemaMetadataDeserializer localDeserializer =
        new KafkaYangJsonSchemaMetadataDeserializer(countingClient);
    localDeserializer.configure(toStringKeyedMap(config), true);

    YangDataDocument doc =
        getRecord(
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test1/test.yang")
                .getFile(),
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test1/valid.json")
                .getFile());

    Headers serializerHeaders = new RecordHeaders();
    byte[] bytes = localSerializer.serialize(topic, serializerHeaders, doc);
    Headers deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);

    YangSchemaAndValue result1 = localDeserializer.deserialize(topic, deserializerHeaders, bytes);
    assertNotNull(result1);

    YangSchemaAndValue result2 = localDeserializer.deserialize(topic, deserializerHeaders, bytes);
    assertNotNull(result2);

    assertEquals(1, callCount.get());
  }

  @Test
  public void deserialize_subjectRegisteredAfterCacheFill_refreshesPreferredSubject()
      throws Exception {
    AtomicInteger callCount = new AtomicInteger();
    SchemaRegistryClient countingClient =
        new MockSchemaRegistryClient(Collections.singletonList(new YangSchemaProvider())) {
          @Override
          public synchronized Collection<SubjectVersion> getAllVersionsById(int id) {
            callCount.incrementAndGet();
            return super.getAllVersionsById(id);
          }
        };
    KafkaYangJsonSchemaSerializer localSerializer =
        new KafkaYangJsonSchemaSerializer(countingClient);
    localSerializer.configure(new HashMap<>(config), true);
    KafkaYangJsonSchemaMetadataDeserializer localDeserializer =
        new KafkaYangJsonSchemaMetadataDeserializer(countingClient);
    localDeserializer.configure(toStringKeyedMap(config), true);

    YangDataDocument doc =
        getRecord(
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test1/test.yang")
                .getFile(),
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test1/valid.json")
                .getFile());
    Headers serializerHeaders = new RecordHeaders();
    byte[] bytes = localSerializer.serialize(topic, serializerHeaders, doc);
    Headers deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);
    int schemaId =
        ByteBuffer.wrap(
                deserializerHeaders
                    .lastHeader(AbstractKafkaYangJsonSchemaSerializer.SCHEMA_ID_KEY)
                    .value())
            .getInt();

    YangSchemaAndValue first = localDeserializer.deserialize(topic, deserializerHeaders, bytes);
    assertNotNull(first);
    assertEquals(1, callCount.get());

    ParsedSchema existingSchema =
        countingClient.getSchemaBySubjectAndId(first.getSubject(), schemaId);
    String lateSubject = "late-subject-key";
    assertEquals(schemaId, countingClient.register(lateSubject, existingSchema));

    YangSchemaAndValue lateResult =
        localDeserializer.deserialize("late-subject", deserializerHeaders, bytes);

    assertNotNull(lateResult);
    assertEquals(lateSubject, lateResult.getSubject());
    assertEquals(
        2, callCount.get(), "A preferred-subject cache miss should refresh the schema-id mapping");
  }

  @Test
  public void subjectVersionCacheStoresImmutableSnapshot() {
    List<SubjectVersion> snapshot =
        AbstractKafkaYangJsonSchemaDeserializer.immutableSubjectVersions(
            new java.util.ArrayList<>(List.of(new SubjectVersion("subject", 1))));

    assertThrows(
        UnsupportedOperationException.class, () -> snapshot.add(new SubjectVersion("other", 1)));
  }
}
