package ch.swisscom.kafka.schemaregistry.yang.benchmark;

import ch.swisscom.kafka.schemaregistry.yang.YangSchema;
import ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider;
import org.openjdk.jmh.annotations.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class YangPluginBenchmark {
    private YangSchemaProvider provider;
    private String schemaString;
    private YangSchema existingSchema;

    private static String readFile(String fileName) {
        ClassLoader classLoader = ClassLoader.getSystemClassLoader();
        InputStream is = classLoader.getResourceAsStream(fileName);
        if (is != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }
        return null;
    }

    @Setup
    public void setup() {
        provider = new YangSchemaProvider();

        schemaString = YangPluginBenchmark.readFile("yang/insa-test.yang");

        existingSchema = (YangSchema) provider.parseSchema(schemaString, Collections.emptyList()).get();
    }

    @Benchmark
    public void testParseSchema() {
        provider.parseSchema(schemaString, Collections.emptyList());
    }

    @Benchmark
    public void testCompatibilityCheck() {
        existingSchema.isBackwardCompatible(existingSchema);
    }
}