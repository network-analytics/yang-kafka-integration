package fr.insa.kafka.streams.serdes.yang.cbor;

import static org.junit.jupiter.api.Assertions.*;

import ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.dom4j.DocumentException;
import org.junit.jupiter.api.Test;
import org.yangcentral.yangkit.common.api.validate.ValidatorResultBuilder;
import org.yangcentral.yangkit.data.api.model.YangDataDocument;
import org.yangcentral.yangkit.data.codec.json.YangDataDocumentJsonParser;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.parser.YangParserException;
import org.yangcentral.yangkit.parser.YangYinParser;

public class KafkaYangCborSerdeTest {

  private static final String ANY_TOPIC = "any-topic";

  private static ObjectMapper objectMapper = new ObjectMapper();

  private static KafkaYangCborSerde<YangDataDocument> createConfiguredSerdeForRecordValues() {
    SchemaRegistryClient schemaRegistryClient =
        new MockSchemaRegistryClient(Collections.singletonList(new YangSchemaProvider()));
    KafkaYangCborSerde<YangDataDocument> serde = new KafkaYangCborSerde<>(schemaRegistryClient);
    Map<String, Object> serdeConfig = new HashMap<>();
    serdeConfig.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "fake");
    serde.configure(serdeConfig, false);
    return serde;
  }

  private YangDataDocument getRecord() {
    YangDataDocument doc;
    String newString = "{\"data\":{\"insa-test:insa-container\":{\"d\":123}}}";
    ObjectMapper mapper = new ObjectMapper();
    try {
      YangSchemaContext schemaContext =
          YangYinParser.parse(this.getClass().getClassLoader().getResource("test.yang").getFile());
      schemaContext.validate();
      JsonNode jsonNode = mapper.readTree(newString);
      doc =
          new YangDataDocumentJsonParser(schemaContext)
              .parse(jsonNode, new ValidatorResultBuilder());
    } catch (DocumentException | IOException | YangParserException e) {
      throw new RuntimeException(e);
    }
    return doc;
  }

  private JsonNode getJsonNode(YangDataDocument doc) {
    JsonNode jsonNode;
    ObjectMapper mapper = new ObjectMapper();
    try {
      jsonNode = mapper.readTree(doc.getDocString());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return jsonNode;
  }

  @Test
  public void shouldRoundTripRecords() throws Exception {
    KafkaYangCborSerde<YangDataDocument> serde = createConfiguredSerdeForRecordValues();
    YangDataDocument record = getRecord();

    YangDataDocument roundTrippedRecord =
        serde
            .deserializer()
            .deserialize(ANY_TOPIC, serde.serializer().serialize(ANY_TOPIC, record));

    assertEquals(getJsonNode(record), getJsonNode(roundTrippedRecord));

    serde.close();
  }

  @Test
  public void shouldRoundTripNullRecordsToNull() {
    KafkaYangCborSerde<YangDataDocument> serde = createConfiguredSerdeForRecordValues();

    YangDataDocument roundTrippedRecord =
        serde.deserializer().deserialize(ANY_TOPIC, serde.serializer().serialize(ANY_TOPIC, null));

    assertNull(roundTrippedRecord);

    serde.close();
  }

  @Test
  public void shouldFailWhenInstantiatedWithNullSchemaRegistryClient() {
    assertThrowsExactly(
        IllegalArgumentException.class,
        () -> new KafkaYangCborSerde<>((SchemaRegistryClient) null));
  }
}
