package ch.swisscom.kafka.schemaregistry.yang.preprocessing;

//import ch.swisscom.kafka.schemaregistry.yang.preprocessing.rules.StubParentRule;
import ch.swisscom.kafka.schemaregistry.yang.preprocessing.rules.UnprefixedUniqueRule;
import java.util.Collection;
import java.util.List;
import org.opendaylight.yangtools.yang.model.spi.source.YangIRSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single entry point for the rules handling vendor-specific customizations that the router's own parser tolerates but
 * yangtools validation rejects. Every rule is a {@link YangSourceRule}, and all of them work in memory on the sources
 * handed to the parser: stored text, schema ids, hashes and API responses always keep the original text.
 *
 * <p>The preprocessor only depends on yangtools, so the schema registry and any consumer that builds an effective model
 * from fetched schemas can share it.
 *
 * <p>Rules, in the order they are applied:
 * <ol>
 *   <li>{@link UnprefixedUniqueRule} - qualifies unprefixed {@code unique} arguments inside deviations.</li>
 *   <li>{@link StubParentRule} - adds a stub parent module when a submodule is registered before its parent.</li>
 * </ol>
 */
public final class YangSourcePreprocessor {
    private static final Logger LOG = LoggerFactory.getLogger(YangSourcePreprocessor.class);

    private final List<YangSourceRule> rules;

    public YangSourcePreprocessor(final List<? extends YangSourceRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Returns a preprocessor applying all the rules shipped with this component.
     *
     * @return the standard preprocessor
     */
    public static YangSourcePreprocessor standard() {
        return new YangSourcePreprocessor(
                List.of(
//                        new StubParentRule(),
                        new UnprefixedUniqueRule()
                )
        );
    }

    /**
     * Prepares the sources of a registration for the parser.
     *
     * @param subject the subject being registered, for log messages
     * @param root the source being registered, one of {@code sources}
     * @param sources all sources of the registration: the source being registered and its references
     * @return the sources to hand to the parser, in the order of the input, followed by any generated source
     */
    public List<YangIRSource> prepare(final String subject, final YangIRSource root,
            final Collection<YangIRSource> sources) {
        return apply(new SourceContext(subject, root), sources);
    }

    /**
     * Preprocesses sources outside of a registration, for example in a consumer that builds an effective model from
     * fetched schemas. Rules that only make sense for a registration, such as {@link StubParentRule}, do nothing.
     *
     * @param sources the sources, modules and submodules
     * @return one source per input, in the same order. A source no rule had to change is returned as is.
     */
    public List<YangIRSource> preprocess(final Collection<YangIRSource> sources) {
        return apply(SourceContext.NONE, sources);
    }

    private List<YangIRSource> apply(final SourceContext context, final Collection<YangIRSource> sources) {
        var current = List.copyOf(sources);
        for (var rule : rules) {
            try {
                current = rule.apply(context, current);
            } catch (RuntimeException e) {
                LOG.warn("Rule {} failed for subject '{}', sources left unchanged", rule.name(), context.subject(), e);
            }
        }
        return current;
    }
}
