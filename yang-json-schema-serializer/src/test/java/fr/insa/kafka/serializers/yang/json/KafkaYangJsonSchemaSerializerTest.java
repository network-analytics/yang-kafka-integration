package fr.insa.kafka.serializers.yang.json;

import static org.junit.jupiter.api.Assertions.*;

import ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Properties;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.dom4j.DocumentException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yangcentral.yangkit.common.api.validate.ValidatorResultBuilder;
import org.yangcentral.yangkit.data.api.model.YangDataDocument;
import org.yangcentral.yangkit.data.codec.json.YangDataDocumentJsonParser;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.parser.YangParserException;
import org.yangcentral.yangkit.parser.YangYinParser;

public class KafkaYangJsonSchemaSerializerTest {

  private final Properties config;
  private final SchemaRegistryClient schemaRegistry;
  private KafkaYangJsonSchemaSerializer serializer;
  private KafkaYangJsonSchemaSerializer noValidationSerializer;
  private KafkaYangJsonSchemaDeserializer deserializer;
  private final String topic;
  private static int idSize = 4;
  private Headers serializerHeaders;
  private Headers deserializerHeaders;

  public KafkaYangJsonSchemaSerializerTest() {
    config = new Properties();
    config.put(KafkaYangJsonSchemaSerializerConfig.AUTO_REGISTER_SCHEMAS, true);
    config.put(KafkaYangJsonSchemaSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, "bogus");
    config.put(KafkaYangJsonSchemaSerializerConfig.YANG_JSON_FAIL_INVALID_SCHEMA, true);

    schemaRegistry =
        new MockSchemaRegistryClient(Collections.singletonList(new YangSchemaProvider()));

    serializer = new KafkaYangJsonSchemaSerializer(schemaRegistry);
    serializer.configure(new HashMap<>(config), true);

    deserializer = new KafkaYangJsonSchemaDeserializer<>(schemaRegistry);
    deserializer.configure(new HashMap<>(config), true);

    Properties noValidationConfig = new Properties(config);
    noValidationConfig.put(KafkaYangJsonSchemaSerializerConfig.AUTO_REGISTER_SCHEMAS, true);
    noValidationConfig.put(KafkaYangJsonSchemaSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, "bogus");
    noValidationConfig.put(
        KafkaYangJsonSchemaSerializerConfig.YANG_JSON_FAIL_INVALID_SCHEMA, false);

    noValidationSerializer = new KafkaYangJsonSchemaSerializer(schemaRegistry);
    noValidationSerializer.configure(new HashMap<>(noValidationConfig), true);

    topic = "test";
  }

  private KafkaYangJsonSchemaDeserializer getDeserializer() {
    KafkaYangJsonSchemaDeserializer des = new KafkaYangJsonSchemaDeserializer<>(schemaRegistry);
    des.configure(new HashMap<>(config), true);
    return des;
  }

  private <T> YangDataDocument getRecord(T o) {
    YangDataDocument doc;
    String newString = "{\"data\":{\"insa-test:insa-container\":{\"d\":" + o + "}}}";
    ObjectMapper mapper = new ObjectMapper();
    try {
      YangSchemaContext schemaContext =
          YangYinParser.parse(
              this.getClass().getClassLoader().getResource("serializer/yangs/test.yang").getFile());
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

  private <T> JsonNode getJsonNode(T o) {
    JsonNode jsonNode;
    String newString = "{\"data\":{\"insa-test:insa-container\":{\"d\":" + o + "}}}";
    ObjectMapper mapper = new ObjectMapper();
    try {
      jsonNode = mapper.readTree(newString);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return jsonNode;
  }

  private JsonNode getJsonNodeFromFile(String json) {
    JsonNode jsonNode;
    ObjectMapper mapper = new ObjectMapper();
    try {
      jsonNode = mapper.readTree(new File(json));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return jsonNode;
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

  private Headers getDeserializationKafkaHeader(Headers serializationHeaders) {
    Headers deserializerHeaders = new RecordHeaders();
    byte[] serializedSchemaId =
        serializationHeaders
            .lastHeader(AbstractKafkaYangJsonSchemaSerializer.SCHEMA_ID_KEY)
            .value();
    int schemaId = ByteBuffer.wrap(serializedSchemaId).getInt();
    deserializerHeaders.add(
        AbstractKafkaYangJsonSchemaSerializer.SCHEMA_ID_KEY,
        ByteBuffer.allocate(idSize).putInt(schemaId).array());
    return deserializerHeaders;
  }

  @Test
  public void singleLeafTest() {
    byte[] bytes;
    YangDataDocument doc = null;

    bytes = serializer.serialize(topic, null);
    assertEquals(null, deserializer.deserialize(topic, bytes));

    doc = getRecord(true);
    serializerHeaders = new RecordHeaders();
    bytes = serializer.serialize(topic, serializerHeaders, doc);
    deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);
    assertEquals(
        getJsonNode(true),
        getJsonNode(deserializer.deserialize(topic, deserializerHeaders, bytes)));

    doc = getRecord(123);
    serializerHeaders = new RecordHeaders();
    bytes = serializer.serialize(topic, serializerHeaders, doc);
    deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);
    assertEquals(
        getJsonNode(123), getJsonNode(deserializer.deserialize(topic, deserializerHeaders, bytes)));

    doc = getRecord(1.23f);
    serializerHeaders = new RecordHeaders();
    bytes = serializer.serialize(topic, serializerHeaders, doc);
    deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);
    assertEquals(
        getJsonNode(1.23f),
        getJsonNode(deserializer.deserialize(topic, deserializerHeaders, bytes)));

    doc = getRecord(123L);
    serializerHeaders = new RecordHeaders();
    bytes = serializer.serialize(topic, serializerHeaders, doc);
    deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);
    assertEquals(
        getJsonNode(123L),
        getJsonNode(deserializer.deserialize(topic, deserializerHeaders, bytes)));

    doc = getRecord("\"abc\"");
    serializerHeaders = new RecordHeaders();
    bytes = serializer.serialize(topic, serializerHeaders, doc);
    deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);
    assertEquals(
        getJsonNode("\"abc\""),
        getJsonNode(deserializer.deserialize(topic, deserializerHeaders, bytes)));
  }

  @Test
  public void serializeNull() {
    assertNull(serializer.serialize("foo", null));
  }

  @Test
  public void test1() {
    byte[] bytes;
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

    JsonNode jsonNode =
        getJsonNodeFromFile(
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test1/valid.json")
                .getFile());

    serializerHeaders = new RecordHeaders();
    bytes = serializer.serialize(topic, serializerHeaders, doc);
    deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);
    assertEquals(
        jsonNode, getJsonNode(deserializer.deserialize(topic, deserializerHeaders, bytes)));
  }

  @Test
  public void test2() {
    YangDataDocument doc =
        getRecord(
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test2/test.yang")
                .getFile(),
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test2/invalid.json")
                .getFile());

    serializerHeaders = new RecordHeaders();
    assertThrowsExactly(
        SerializationException.class, () -> serializer.serialize(topic, serializerHeaders, doc));
  }

  @Test
  public void test3() {
    byte[] bytes;
    YangDataDocument doc =
        getRecord(
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test3/test.yang")
                .getFile(),
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test3/invalid.json")
                .getFile());

    serializerHeaders = new RecordHeaders();
    bytes =
        Assertions.assertDoesNotThrow(
            () -> noValidationSerializer.serialize(topic, serializerHeaders, doc));
    deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);
    assertThrowsExactly(
        SerializationException.class,
        () -> deserializer.deserialize(topic, deserializerHeaders, bytes));
  }

  @Test
  public void test4() {
    byte[] bytes;
    YangDataDocument doc =
        getRecord(
            this.getClass().getClassLoader().getResource("serializer/json/test4/yangs").getFile(),
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test4/valid.json")
                .getFile());

    JsonNode jsonNode =
        getJsonNodeFromFile(
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test4/valid.json")
                .getFile());
    serializerHeaders = new RecordHeaders();
    bytes = serializer.serialize(topic, serializerHeaders, doc);

    deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);
    assertEquals(
        jsonNode, getJsonNode(deserializer.deserialize(topic, deserializerHeaders, bytes)));
  }

  @Test
  public void test5() {
    YangDataDocument doc =
        getRecord(
            this.getClass().getClassLoader().getResource("serializer/json/test5/yangs").getFile(),
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test5/invalid.json")
                .getFile());
    serializerHeaders = new RecordHeaders();
    assertThrowsExactly(
        SerializationException.class, () -> serializer.serialize(topic, serializerHeaders, doc));
  }

  @Test
  public void test6() {
    byte[] bytes;
    YangDataDocument doc =
        getRecord(
            this.getClass().getClassLoader().getResource("serializer/json/test6/yangs").getFile(),
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test6/invalid.json")
                .getFile());
    serializerHeaders = new RecordHeaders();
    bytes =
        Assertions.assertDoesNotThrow(
            () -> noValidationSerializer.serialize(topic, serializerHeaders, doc));

    deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);
    assertThrowsExactly(
        SerializationException.class,
        () -> deserializer.deserialize(topic, deserializerHeaders, bytes));
  }

  @Test
  public void test7() {
    byte[] bytes;
    YangDataDocument doc =
        getRecord(
            this.getClass().getClassLoader().getResource("serializer/json/test7/yangs").getFile(),
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test7/valid.json")
                .getFile());

    JsonNode jsonNode =
        getJsonNodeFromFile(
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test7/valid.json")
                .getFile());
    serializerHeaders = new RecordHeaders();
    bytes = serializer.serialize(topic, serializerHeaders, doc);

    deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);
    assertEquals(
        jsonNode, getJsonNode(deserializer.deserialize(topic, deserializerHeaders, bytes)));
  }

  @Test
  public void test8() {
    byte[] bytes;
    YangDataDocument doc =
        getRecord(
            this.getClass().getClassLoader().getResource("serializer/json/test8/yangs").getFile(),
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test8/valid.json")
                .getFile());

    JsonNode jsonNode =
        getJsonNodeFromFile(
            this.getClass()
                .getClassLoader()
                .getResource("serializer/json/test8/valid.json")
                .getFile());
    serializerHeaders = new RecordHeaders();
    bytes = serializer.serialize(topic, serializerHeaders, doc);

    deserializerHeaders = getDeserializationKafkaHeader(serializerHeaders);
    assertEquals(
        jsonNode, getJsonNode(deserializer.deserialize(topic, deserializerHeaders, bytes)));
  }
}
