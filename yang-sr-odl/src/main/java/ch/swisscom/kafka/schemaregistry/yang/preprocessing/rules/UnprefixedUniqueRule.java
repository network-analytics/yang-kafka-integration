package ch.swisscom.kafka.schemaregistry.yang.preprocessing.rules;

import ch.swisscom.kafka.schemaregistry.yang.preprocessing.PerSourceRule;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.opendaylight.yangtools.yang.ir.IRArgument;
import org.opendaylight.yangtools.yang.ir.IRKeyword;
import org.opendaylight.yangtools.yang.ir.IRStatement;
import org.opendaylight.yangtools.yang.ir.StringEscaping;
import org.opendaylight.yangtools.yang.model.api.source.SourceSyntaxException;
import org.opendaylight.yangtools.yang.model.spi.source.YangIRSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UnprefixedUniqueRule extends PerSourceRule {
    private static final Logger LOG = LoggerFactory.getLogger(UnprefixedUniqueRule.class);

    private static final Set<String> DATA_DEFINITIONS =
        Set.of("container", "leaf", "leaf-list", "list", "choice", "anydata", "anyxml");

    private final boolean rewriteDelete;

    public UnprefixedUniqueRule() {
        this(false);
    }

    /**
     * Creates the rule.
     *
     * @param rewriteDelete whether {@code unique} inside {@code deviate delete} is qualified as well. Only right if
     *                      the deviation target writes its own {@code unique} arguments with a prefix.
     */
    public UnprefixedUniqueRule(final boolean rewriteDelete) {
        this.rewriteDelete = rewriteDelete;
    }

    @Override
    public String name() {
        return "qualify-unprefixed-unique";
    }

    @Override
    public YangIRSource apply(final YangIRSource source) {
        final var root = source.statement();
        final var escaping = escapingOf(root);
        final var moduleName = moduleNameOf(source, escaping);

        final var rewritten = rewriteChildren(root, child -> isKeyword(child, "deviation")
            ? rewriteDeviation(root, child, escaping, moduleName) : child);
        if (rewritten == root) {
            return source;
        }
        try {
            return YangIRSource.of(source.sourceId(), rewritten, source.symbolicName());
        } catch (SourceSyntaxException e) {
            LOG.warn("Cannot rebuild source {}, leaving it unchanged", source.sourceId(), e);
            return source;
        }
    }

    private IRStatement rewriteDeviation(final IRStatement root, final IRStatement deviation,
            final StringEscaping escaping, final String moduleName) {
        final var targetPath = argumentOf(deviation, escaping).orElse(null);
        if (targetPath == null) {
            return deviation;
        }
        final var prefix = lastStepPrefix(targetPath);
        if (prefix == null) {
            // Unprefixed means the target is in the deviating module, where unprefixed names are correct
            return deviation;
        }

        // Computed only when a unique actually needs it
        final Supplier<Set<String>> augmented = Suppliers.memoize(() -> augmentedChildren(root, targetPath, escaping));

        return rewriteChildren(deviation, deviate -> {
            if (!isKeyword(deviate, "deviate")) {
                return deviate;
            }
            if (!rewriteDelete && argumentOf(deviate, escaping).filter("delete"::equals).isPresent()) {
                return deviate;
            }
            return rewriteChildren(deviate, unique -> {
                if (!isKeyword(unique, "unique")) {
                    return unique;
                }
                final var original = argumentOf(unique, escaping).orElse(null);
                final var qualified = original == null ? null : qualify(original, prefix, augmented);
                if (qualified == null) {
                    return unique;
                }
                LOG.debug("Qualified unique argument '{}' -> '{}' in deviation '{}' (module {}, line {})",
                    original, qualified, targetPath, moduleName, unique.startLine());
                return IRStatement.of(unique.keyword(), IRArgument.singleQuoted(qualified), unique.startLine(),
                    unique.startColumn(), ImmutableList.copyOf(unique.statements()));
            });
        });
    }

    /**
     * Qualifies the unprefixed steps of a {@code unique} argument.
     *
     * @return the new argument, or {@code null} if nothing had to be qualified
     */
    private static String qualify(final String argument, final String prefix,
            final Supplier<Set<String>> augmented) {
        final var trimmed = argument.strip();
        if (trimmed.isEmpty()) {
            return null;
        }

        boolean changed = false;
        final var tokens = new ArrayList<String>();
        for (var token : trimmed.split("[ \t\r\n]+")) {
            final var steps = token.split("/", -1);
            if (steps[0].indexOf(':') < 0 && augmented.get().contains(steps[0])) {
                // A node added by this very module and so in this module's namespace, as is anything below it
                LOG.debug("Not qualifying '{}', the deviating module augments the deviation target with '{}'",
                    token, steps[0]);
                tokens.add(token);
                continue;
            }

            final var qualifiedSteps = new ArrayList<String>(steps.length);
            for (var step : steps) {
                if (step.isEmpty() || step.indexOf(':') >= 0) {
                    qualifiedSteps.add(step);
                } else {
                    qualifiedSteps.add(prefix + ':' + step);
                    changed = true;
                }
            }
            tokens.add(String.join("/", qualifiedSteps));
        }
        return changed ? String.join(" ", tokens) : null;
    }

    /**
     * Collects the names of the data nodes that {@code augment} statements of this module add to the given target,
     * including those coming from groupings of this module used by such an augment.
     */
    private static Set<String> augmentedChildren(final IRStatement root, final String targetPath,
            final StringEscaping escaping) {
        final var names = new HashSet<String>();
        final var wanted = stripWhitespace(targetPath);
        for (var statement : root.statements()) {
            if (isKeyword(statement, "augment")
                    && argumentOf(statement, escaping).map(UnprefixedUniqueRule::stripWhitespace)
                        .filter(wanted::equals).isPresent()) {
                collectDataNodeNames(root, statement, escaping, names, new HashSet<>());
            }
        }
        return names;
    }

    private static void collectDataNodeNames(final IRStatement root, final IRStatement parent,
            final StringEscaping escaping, final Set<String> names, final Set<String> visitedGroupings) {
        for (var child : parent.statements()) {
            if (!(child.keyword() instanceof IRKeyword.Unqualified keyword)) {
                continue;
            }
            final var identifier = keyword.identifier();
            if (DATA_DEFINITIONS.contains(identifier)) {
                argumentOf(child, escaping).ifPresent(names::add);
            } else if ("uses".equals(identifier)) {
                final var grouping = argumentOf(child, escaping).orElse(null);
                if (grouping == null || grouping.indexOf(':') >= 0) {
                    LOG.debug("Cannot tell which nodes 'uses {}' adds to an augment, assuming none", grouping);
                } else if (visitedGroupings.add(grouping)) {
                    root.statements().stream()
                        .filter(statement -> isKeyword(statement, "grouping")
                            && argumentOf(statement, escaping).filter(grouping::equals).isPresent())
                        .findFirst()
                        .ifPresentOrElse(
                            definition -> collectDataNodeNames(root, definition, escaping, names, visitedGroupings),
                            () -> LOG.debug("Grouping '{}' not found in module, assuming it adds no nodes",
                                grouping));
                }
            } else if ("case".equals(identifier)) {
                collectDataNodeNames(root, child, escaping, names, visitedGroupings);
            }
        }
    }

    private static IRStatement rewriteChildren(final IRStatement statement,
            final UnaryOperator<IRStatement> mapper) {
        final var children = statement.statements();
        List<IRStatement> rebuilt = null;
        for (int i = 0; i < children.size(); i++) {
            final var child = children.get(i);
            final var replacement = mapper.apply(child);
            if (replacement != child && rebuilt == null) {
                rebuilt = new ArrayList<>(children.subList(0, i));
            }
            if (rebuilt != null) {
                rebuilt.add(replacement);
            }
        }
        return rebuilt == null ? statement
            : IRStatement.of(statement.keyword(), statement.argument(), statement.startLine(),
                statement.startColumn(), ImmutableList.copyOf(rebuilt));
    }

    private static String lastStepPrefix(final String path) {
        final var stripped = stripWhitespace(path);
        final var lastStep = stripped.substring(stripped.lastIndexOf('/') + 1);
        final int colon = lastStep.indexOf(':');
        return colon <= 0 ? null : lastStep.substring(0, colon);
    }

    private static String stripWhitespace(final String str) {
        return str.replaceAll("\\s+", "");
    }

    private static boolean isKeyword(final IRStatement statement, final String identifier) {
        return statement.keyword() instanceof IRKeyword.Unqualified keyword
            && identifier.equals(keyword.identifier());
    }

    private static Optional<String> argumentOf(final IRStatement statement, final StringEscaping escaping) {
        final var argument = statement.argument();
        if (argument == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(argument.asString(escaping));
        } catch (ParseException e) {
            // Left to the parser, which reports it properly
            LOG.debug("Ignoring statement '{}' at line {}: {}", statement.keyword().asStringDeclaration(),
                statement.startLine(), e.getMessage());
            return Optional.empty();
        }
    }

    private static StringEscaping escapingOf(final IRStatement root) {
        for (var child : root.statements()) {
            if (isKeyword(child, "yang-version")) {
                return argumentOf(child, StringEscaping.RFC6020).filter("1.1"::equals).isPresent()
                    ? StringEscaping.RFC7950 : StringEscaping.RFC6020;
            }
        }
        return StringEscaping.RFC6020;
    }

    private static String moduleNameOf(final YangIRSource source, final StringEscaping escaping) {
        return argumentOf(source.statement(), escaping).orElseGet(() -> source.sourceId().name().getLocalName());
    }
}
