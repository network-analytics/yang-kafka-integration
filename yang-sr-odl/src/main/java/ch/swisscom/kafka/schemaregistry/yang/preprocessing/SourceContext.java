package ch.swisscom.kafka.schemaregistry.yang.preprocessing;

import org.eclipse.jdt.annotation.Nullable;
import org.opendaylight.yangtools.yang.model.spi.source.YangIRSource;

public record SourceContext(String subject, @Nullable YangIRSource root) {

    public static final SourceContext NONE = new SourceContext("", null);
}
