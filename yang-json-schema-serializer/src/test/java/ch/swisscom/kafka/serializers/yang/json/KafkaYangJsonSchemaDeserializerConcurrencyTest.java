package ch.swisscom.kafka.serializers.yang.json;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.yangcentral.yangkit.common.api.validate.ValidatorResultBuilder;
import org.yangcentral.yangkit.data.api.model.YangDataDocument;
import org.yangcentral.yangkit.data.codec.json.YangDataDocumentJsonParser;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.parser.YangYinParser;

class KafkaYangJsonSchemaDeserializerConcurrencyTest {

  @Test
  void validationOnOneSharedContextIsSerialized() throws Exception {
    ClassLoader classLoader = getClass().getClassLoader();
    String yangPath =
        new File(classLoader.getResource("serializer/json/test1/test.yang").toURI()).getPath();
    File jsonFile = new File(classLoader.getResource("serializer/json/test1/valid.json").toURI());
    YangSchemaContext context = YangYinParser.parse(yangPath);
    context.validate();
    JsonNode jsonNode = new ObjectMapper().readTree(jsonFile);
    YangDataDocumentJsonParser parser = new YangDataDocumentJsonParser(context);
    List<YangDataDocument> documents = new ArrayList<>();
    for (int i = 0; i < 32; i++) {
      YangDataDocument document = parser.parse(jsonNode.deepCopy(), new ValidatorResultBuilder());
      document.update();
      documents.add(document);
    }

    ExecutorService executor = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (YangDataDocument document : documents) {
        results.add(
            executor.submit(
                () -> {
                  start.await();
                  return AbstractKafkaYangJsonSchemaDeserializer.validateDocument(context, document)
                      .isOk();
                }));
      }
      start.countDown();
      for (Future<Boolean> result : results) {
        assertTrue(result.get());
      }
    } finally {
      executor.shutdownNow();
    }
  }
}
