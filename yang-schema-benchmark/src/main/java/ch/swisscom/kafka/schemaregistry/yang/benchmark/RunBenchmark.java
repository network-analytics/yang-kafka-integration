package ch.swisscom.kafka.schemaregistry.yang.benchmark;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class RunBenchmark {

    public static void main(String[] args) throws Exception {

        Options opt = new OptionsBuilder()
                    .include(YangPluginBenchmark.class.getSimpleName())
                    .forks(0)
                    .build();

        new Runner(opt).run();
    }
}
