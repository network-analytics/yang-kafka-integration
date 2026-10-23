package ch.swisscom.kafka.schemaregistry.yang.preprocessing;

import java.util.List;
import org.opendaylight.yangtools.yang.model.spi.source.YangIRSource;

/**
 * A workaround for a vendor-specific customization, applied by {@link YangSourcePreprocessor} to the set of sources handed to the
 * yangtools parser. A rule either rewrites sources (see {@link PerSourceRule}) or adds sources, and must never modify
 * its input - sources are immutable, a changed source is returned as a new instance. Everything it produces is only
 * meant for the parser and is never stored or returned to clients.
 *
 * <p>A rule must be deterministic and idempotent, and minimal: it changes only what the customization requires.
 */
public interface YangSourceRule {
    String name();

    List<YangIRSource> apply(SourceContext context, List<YangIRSource> sources);
}
