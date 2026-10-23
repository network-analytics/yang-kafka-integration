package ch.swisscom.kafka.schemaregistry.yang.preprocessing;

import java.util.ArrayList;
import java.util.List;
import org.opendaylight.yangtools.yang.model.spi.source.YangIRSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A rule that rewrites each source on its own, independently of the others. */
public abstract class PerSourceRule implements YangSourceRule {
    private static final Logger LOG = LoggerFactory.getLogger(PerSourceRule.class);

    /**
     * Rewrites a single source.
     *
     * @param source the source, a module or a submodule
     * @return the same instance if nothing had to change, otherwise a new source with the same source identifier
     */
    public abstract YangIRSource apply(YangIRSource source);

    @Override
    public final List<YangIRSource> apply(final SourceContext context, final List<YangIRSource> sources) {
        final var result = new ArrayList<YangIRSource>(sources.size());
        for (var source : sources) {
            YangIRSource rewritten;
            try {
                rewritten = apply(source);
            } catch (RuntimeException e) {
                LOG.warn("Rule {} failed on {}, source left unchanged", name(), source.sourceId(), e);
                rewritten = source;
            }
            result.add(rewritten);
        }
        return result;
    }
}
