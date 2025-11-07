package ch.swisscom.kafka.formatter.yang.json;

import static org.junit.jupiter.api.Assertions.*;

import ch.swisscom.kafka.schemaregistry.yang.YangSchema;
import ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider;
import ch.swisscom.kafka.serializers.yang.json.AbstractKafkaYangJsonSchemaSerializer;
import ch.swisscom.kafka.serializers.yang.json.KafkaYangJsonSchemaDeserializerConfig;
import ch.swisscom.kafka.serializers.yang.json.KafkaYangJsonSchemaSerializerTest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Optional;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.dom4j.DocumentException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.parser.YangParserException;
import org.yangcentral.yangkit.parser.YangYinParser;

public class KafkaYangJsonSchemaFormatterTest {

  private static Properties props;
  private static YangJsonSchemaMessageFormatter formatter;
  private static YangSchema recordSchema = null;
  private static String url = "mock://test";
  private static ObjectMapper objectMapper = new ObjectMapper();
  private static int idSize = 4;
  private static SchemaRegistryClient schemaRegistry = null;

  @BeforeAll
  public static void setUp() {
    props = new Properties();
    props.put(KafkaYangJsonSchemaDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, url);
    formatter = new YangJsonSchemaMessageFormatter();
    formatter.init(props);
    schemaRegistry =
        new MockSchemaRegistryClient(Collections.singletonList(new YangSchemaProvider()));
    YangSchemaContext schemaContext;
    String schemaString;
    try {
      schemaContext =
          YangYinParser.parse(
              KafkaYangJsonSchemaSerializerTest.class
                  .getClassLoader()
                  .getResource("formatter/yangs/test.yang")
                  .getFile());
      schemaContext.validate();
      schemaString = schemaContext.getModules().get(0).getOriginalString();
    } catch (DocumentException | IOException | YangParserException e) {
      throw new RuntimeException(e);
    }
    recordSchema =
        (YangSchema)
            schemaRegistry
                .parseSchema(YangSchema.TYPE, schemaString, Collections.emptyList())
                .get();
  }

  @AfterAll
  public static void tearDown() {
    MockSchemaRegistry.dropScope("test");
  }

  @Test
  public void testKafkaYangJsonValueFormatter() throws JsonProcessingException {
    String input = "{\"data\":{\"insa-test:insa-container\":{\"d\": \"test\"}}}";

    InputStream reader = new ByteArrayInputStream(input.getBytes());
    YangJsonSchemaMessageReader yangJsonSchemaMessageReader =
        new YangJsonSchemaMessageReader(
            url, null, recordSchema, "topic1", false, false, true, false);

    ProducerRecord<byte[], byte[]> message = yangJsonSchemaMessageReader.readRecords(reader).next();
    byte[] serializedValue = message.value();

    byte[] serializedSchemaId =
        message.headers().lastHeader(AbstractKafkaYangJsonSchemaSerializer.SCHEMA_ID_KEY).value();
    int schemaId = ByteBuffer.wrap(serializedSchemaId).getInt();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream ps = new PrintStream(baos);

    Headers headers = new RecordHeaders();
    headers.add(
        AbstractKafkaYangJsonSchemaSerializer.SCHEMA_ID_KEY,
        ByteBuffer.allocate(idSize).putInt(schemaId).array());
    ConsumerRecord<byte[], byte[]> crecord =
        new ConsumerRecord<>(
            "topic1",
            0,
            200,
            1000,
            TimestampType.LOG_APPEND_TIME,
            0,
            serializedValue.length,
            null,
            serializedValue,
            headers,
            Optional.empty());

    formatter.writeTo(crecord, ps);

    String output = baos.toString();

    assertEquals(objectMapper.readTree(input), objectMapper.readTree(output));
  }

  @Test
  public void testInvalidFormat() {
    String input = "{\"data\":{\"insa-test:insa-container\":{\"d\": \"test\"";

    InputStream reader = new ByteArrayInputStream(input.getBytes());
    YangJsonSchemaMessageReader yangJsonSchemaMessageReader =
        new YangJsonSchemaMessageReader(
            url, null, recordSchema, "topic1", false, false, true, false);

    assertThrowsExactly(
        SerializationException.class, () -> yangJsonSchemaMessageReader.readRecords(reader).next());
  }
}
