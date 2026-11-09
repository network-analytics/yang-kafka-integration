package ch.swisscom.kafka.schemaregistry.yang.compatibility;

import ch.swisscom.kafka.schemaregistry.util.YangSchemaProviderMetrics;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yangcentral.yangkit.comparator.yangtools.CompareType;
import org.yangcentral.yangkit.comparator.yangtools.CompatibilityReport;
import org.yangcentral.yangkit.comparator.yangtools.YangComparator;
import org.yangcentral.yangkit.comparator.yangtools.change.Change;
import org.yangcentral.yangkit.comparator.yangtools.change.Compatibility;
import org.yangcentral.yangkit.comparator.yangtools.change.CompatibilityInfo;
import org.yangcentral.yangkit.comparator.yangtools.change.NodeChange;
import org.yangcentral.yangkit.comparator.yangtools.change.StatementInfo;
import org.yangcentral.yangkit.comparator.yangtools.schema.YangSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Decides whether a YANG module is backward compatible to its previous version.
 */
public final class YangCompatibilityChecker {
    private static final Logger log = LoggerFactory.getLogger(YangCompatibilityChecker.class);

    /** Provider config (in schema-registry.properties: {@code schema.providers.<key>}) to accept {@code UNKNOWN}. */
    public static final String ALLOW_UNKNOWN_CONFIG = "yang.compatibility.allow.unknown";

    /** The most findings which are reported, the registry puts them in the body of the error response. */
    public static final int MAX_MESSAGES = 50;

    private final boolean allowUnknown;
    private final YangSchemaProviderMetrics metrics;

    /**
     * @param allowUnknown whether a change which the comparator cannot judge is accepted
     * @param metrics receives the outcome of every check, may be {@code null}
     */
    public YangCompatibilityChecker(boolean allowUnknown, YangSchemaProviderMetrics metrics) {
        this.allowUnknown = allowUnknown;
        this.metrics = metrics;
    }

    /** Rejects what the comparator cannot judge, records no metrics. */
    public static YangCompatibilityChecker strict() {
        return new YangCompatibilityChecker(false, null);
    }

    public boolean allowUnknown() {
        return allowUnknown;
    }

    /**
     * Checks whether the current module is backward compatible to the previous one. Only the named module is
     * compared; the modules which it imports are part of the models and show up where the module uses them.
     * It fails closed: if the comparison cannot be performed, the module is reported as not compatible.
     *
     * @return the reasons why it is not compatible, empty if it is
     */
    public List<String> check(EffectiveModelContext previous, String previousModule,
                              EffectiveModelContext current, String currentModule) {
        List<String> messages;
        try {
            CompatibilityReport report = new YangComparator(
                    YangSchema.of(previous, List.of(previousModule)), YangSchema.of(current, List.of(currentModule)))
                    .compare(CompareType.COMPATIBLE_CHECK);
            messages = messages(report);
            log.debug("Compared {} to {}: {}, {} change(s)", currentModule, previousModule,
                    report.getCompatibility(), report.getChanges().size());
        } catch (RuntimeException e) {
            log.error("Compatibility check of {} against {} failed", currentModule, previousModule, e);
            messages = Collections.singletonList("Compatibility check of module " + currentModule
                    + " could not be performed: " + e);
        }
        if (metrics != null) {
            metrics.recordCompatibilityCheck(messages.isEmpty());
        }
        return messages;
    }

    private List<String> messages(CompatibilityReport report) {
        Compatibility verdict = report.getCompatibility();
        if (verdict == Compatibility.BC || verdict == Compatibility.UNKNOWN && allowUnknown) {
            return Collections.emptyList();
        }
        List<Finding> problems = new ArrayList<>();
        for (Change change : report.getChanges()) {
            flatten(change, null, problems);
        }
        // the changes which break clients first, they are what is cut off when there are many
        problems.removeIf(f -> f.verdict() == Compatibility.BC || f.verdict() == Compatibility.UNKNOWN && allowUnknown);
        problems.sort(Comparator.comparing(Finding::verdict).reversed());

        List<String> messages = new ArrayList<>();
        for (Finding problem : problems.subList(0, Math.min(problems.size(), MAX_MESSAGES))) {
            messages.add(problem.toString());
        }
        if (problems.size() > MAX_MESSAGES) {
            messages.add("... and " + (problems.size() - MAX_MESSAGES) + " more incompatible change(s)");
        }
        if (messages.isEmpty()) {
            // not BC, but no single change to blame: still reject
            messages.add("Module is " + verdict.getName());
        }
        return messages;
    }

    /** A node which has changed is reported by the changes of its statements, the details. */
    private static void flatten(Change change, String path, List<Finding> out) {
        if (change instanceof NodeChange node) {
            path = node.getSchemaPath();
            if (!node.getStatementChanges().isEmpty()) {
                node.getStatementChanges().forEach(statement -> flatten(statement, node.getSchemaPath(), out));
                return;
            }
        }
        StatementInfo left = change.getLeft();
        StatementInfo right = change.getRight();
        StatementInfo subject = right != null ? right : left;
        CompatibilityInfo info = change.getCompatibilityInfo();
        out.add(new Finding(
                info == null ? Compatibility.UNKNOWN : info.getCompatibility(),
                change.getChangeType().name(),
                path,
                subject == null ? null : subject.outputStatement(),
                left != null && right != null ? left.outputStatement() : null,
                info == null ? null : info.getDescription(),
                subject == null ? null : subject.position()));
    }

    private record Finding(Compatibility verdict, String change, String path, String statement, String previous,
                           String reason, String position) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(verdict.getName()).append(": ").append(change);
            if (path != null) {
                sb.append(' ').append(path);
            }
            if (statement != null) {
                sb.append(" '").append(statement).append('\'');
            }
            if (previous != null) {
                sb.append(" (was '").append(previous).append("')");
            }
            if (reason != null && !reason.isBlank()) {
                sb.append(" - ").append(reason);
            }
            // the provider does not retain declaration references, then the comparator has no position to give
            if (position != null && !"unknown".equals(position)) {
                sb.append(" [").append(position).append(']');
            }
            return sb.toString();
        }
    }
}
