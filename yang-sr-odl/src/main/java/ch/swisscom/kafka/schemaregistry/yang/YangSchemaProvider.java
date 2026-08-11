package ch.swisscom.kafka.schemaregistry.yang;

import ch.swisscom.kafka.schemaregistry.util.ParseErrorReason;
import ch.swisscom.kafka.schemaregistry.util.YangSchemaProviderMetrics;
import io.confluent.kafka.schemaregistry.AbstractSchemaProvider;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.SchemaVersionFetcher;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.parser.api.YangParserConfiguration;
import org.opendaylight.yangtools.yang.parser.api.YangParserException;
import org.opendaylight.yangtools.yang.parser.api.YangParserFactory;
import org.opendaylight.yangtools.yang.parser.api.YangSyntaxErrorException;
import org.opendaylight.yangtools.yang.parser.impl.DefaultYangParserFactory;
import org.opendaylight.yangtools.yang.model.api.source.SourceIdentifier;
import org.opendaylight.yangtools.yang.model.spi.source.StringYangTextSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class YangSchemaProvider extends AbstractSchemaProvider {
    private static final Logger log = LoggerFactory.getLogger(YangSchemaProvider.class);

    private static final Pattern SUBMODULE_PATTERN = Pattern.compile("submodule\\s+([\\w.-]+)\\s*\\{");
    private static final Pattern BELONGS_TO_PATTERN = Pattern.compile("belongs-to\\s+([\\w.-]+)\\s*\\{");
    private static final Pattern PREFIX_PATTERN = Pattern.compile("\\bprefix\\s+\"?([\\w.-]+)\"?\\s*;");
    private static final Pattern OWN_DECLARATION_PATTERN = Pattern.compile("\\b(module|submodule)\\s+([\\w.-]+)\\s*\\{");
    private static final Pattern DEPENDENCY_PATTERN = Pattern.compile("\\b(import|include|belongs-to)\\s+\"?([\\w.-]+)\"?\\s*[;{]");
    private static final Pattern DEVIATION_PATTERN = Pattern.compile("deviation\\s+\"([^\"]+)\"\\s*\\{");
    private static final Pattern UNIQUE_PATTERN = Pattern.compile("(unique\\s+)\"([^\"]+)\"(\\s*;)");

    private final YangParserFactory parserFactory = new DefaultYangParserFactory();
    private final YangSchemaProviderMetrics metrics;

    public YangSchemaProvider() {
        this.metrics = new YangSchemaProviderMetrics(() -> 0, 0, () -> 0, 0);
    }

    @Override
    public String schemaType() {
        return YangSchema.TYPE;
    }

    @Override
    public ParsedSchema parseSchemaOrElseThrow(Schema schema, boolean isNew, boolean normalize) {
        metrics.recordRequest();
        try {
            return parse(schema);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error parsing YANG schema for subject {}: {}", schema.getSubject(), describe(e), e);
            metrics.recordParseError(ParseErrorReason.UNEXPECTED);
            throw new RuntimeException(
                    "Failed to parse YANG schema " + schema.getSubject() + ": " + describe(e), e);
        }
    }

    private YangSchema parse(Schema schema) throws IOException {
        Map<String, String> resolvedRefs;
        Map<String, SchemaReference> directRefs = new LinkedHashMap<>();
        if (schema.getReferences() != null || !schema.getReferences().isEmpty()) {
            for (SchemaReference reference : schema.getReferences()) {
                if (reference.getName() != null) {
                    directRefs.put(reference.getName(), reference);
                }
            }
        }
        try {
            // Wrap in a mutable, order-preserving map: resolveBelongsToParentIfMissing() below may
            // need to add one more entry (the submodule's belongs-to parent, auto-resolved by
            // naming convention) on top of whatever schema.getReferences() declared.
            resolvedRefs = new LinkedHashMap<>(resolveReferences(schema));
        } catch (Exception e) {
            log.error("Failed to resolve references for subject {}: {}", schema.getSubject(), describe(e), e);
            metrics.recordParseError(ParseErrorReason.UNRESOLVABLE_REFERENCE);
            throw new RuntimeException(
                    "Failed to resolve schema references for subject " + schema.getSubject()
                            + ": " + describe(e), e);
        }

        Set<String> autoResolvedNames = new HashSet<>();

        resolveBelongsToParentIfMissing(schema, resolvedRefs, directRefs, autoResolvedNames);
        resolveMissingDependencies(schema, resolvedRefs, directRefs, autoResolvedNames);
        removeSelfReferenceCollisions(schema, resolvedRefs);

        EffectiveModelContext context;
        try {
            context = buildEffectiveModelWithRetry(schema, resolvedRefs, directRefs, autoResolvedNames);
        } catch (YangParserException e) {
            log.error("Error parsing YANG schema for subject {}: {}", schema.getSubject(), describe(e), e);
            metrics.recordParseError(ParseErrorReason.PARSE_SCHEMA);
            throw new RuntimeException("Invalid YANG schema, subject: " + schema.getSubject() + ": " + describe(e), e);
        }

        return new YangSchema(schema.getSchema(), schema.getVersion(), context, schema.getReferences(), null, null);
    }

    /**
     * Builds a fresh YangParser, adds main schema + every entry in resolvedRefs as sources and builds effective model.
     */
    private EffectiveModelContext buildEffectiveModel(Schema schema, Map<String, String> resolvedRefs)
            throws YangParserException, IOException {
        var parser = parserFactory.createParser(YangParserConfiguration.DEFAULT);

        parser.addSource(new StringYangTextSource(new SourceIdentifier(schema.getSubject()),
                fixUnprefixedDeviationUniqueArguments(schema.getSchema())));

        for (Map.Entry<String, String> entry : resolvedRefs.entrySet()) {
            parser.addSource(new StringYangTextSource(new SourceIdentifier(entry.getKey()),
                    fixUnprefixedDeviationUniqueArguments(entry.getValue())));
        }
        // NOTE: this single call both resolves imports/augments/deviations/groupings against
        // every added source (main schema + all references above) AND fully semantically
        // validates the resulting model
        // TODO: test more for here.
        return parser.buildEffectiveModel();
    }

    private EffectiveModelContext buildEffectiveModelWithRetry(
            Schema schema,
            Map<String, String> resolvedRefs,
            Map<String, SchemaReference> directRefs,
            Set<String> autoResolvedNames
    ) throws YangParserException, IOException {
        try {
            return buildEffectiveModel(schema, resolvedRefs);
        } catch (YangParserException originalFailure) {
            SchemaVersionFetcher fetcher = schemaVersionFetcher();
            if (fetcher == null) {
                throw originalFailure;
            }
            for (Map.Entry<String, SchemaReference> entry : directRefs.entrySet()) {
                String name = entry.getKey();
                SchemaReference reference = entry.getValue();
                if (!resolvedRefs.containsKey(name)
                        || reference.getSubject() == null || reference.getVersion() == null) {
                    continue; // not actually used as a source, or nothing to bump
                }

                Schema latest;
                try {
                    latest = fetcher.getByVersion(reference.getSubject(), -1, true);
                } catch (RuntimeException fetchFailure) {
                    continue;
                }
                if (latest == null || latest.getVersion() == null || latest.getVersion().equals(reference.getVersion())) {
                    continue; // already at latest (or couldn't tell) - retrying would change nothing
                }

                log.warn("Full model build failed for subject '{}' with reference '{}' pinned at "
                                + "version {}; retrying with its latest version {} instead, since a "
                                + "stale reference version (rather than a genuine structural "
                                + "problem) is a known recurring cause: {}",
                        schema.getSubject(), name, reference.getVersion(), latest.getVersion(),
                        describe(originalFailure));

                Map<String, String> retryRefs = new LinkedHashMap<>(resolvedRefs);
                retryRefs.put(name, latest.getSchema());
                Map<String, SchemaReference> retryDirectRefs = new LinkedHashMap<>(directRefs);
                retryDirectRefs.put(name, new SchemaReference(name, reference.getSubject(), latest.getVersion()));
                Set<String> retryAutoResolvedNames = new HashSet<>(autoResolvedNames);

                discoverAdditionalDependencies(
                        new DependencySource(reference.getSubject(), latest.getVersion(), latest.getSchema()),
                        retryRefs, retryDirectRefs, retryAutoResolvedNames);
                try {
                    EffectiveModelContext context = buildEffectiveModel(schema, retryRefs);
                    log.info("Recovered from a full-model build failure for subject '{}' by "
                                    + "upgrading reference '{}' from version {} to {}",
                            schema.getSubject(), name, reference.getVersion(), latest.getVersion());
                    resolvedRefs.putAll(retryRefs);
                    directRefs.putAll(retryDirectRefs);
                    autoResolvedNames.addAll(retryAutoResolvedNames);
                    return context;
                } catch (YangParserException retryFailure) {
                    log.debug("Retry with '{}' upgraded to version {} still failed for subject "
                                    + "'{}': {}",
                            name, latest.getVersion(), schema.getSubject(), describe(retryFailure));
                }
            }

            for (String name : autoResolvedNames) {
                SchemaReference reference = directRefs.get(name);
                if (reference == null || !resolvedRefs.containsKey(name)
                        || reference.getSubject() == null || reference.getVersion() == null) {
                    continue;
                }
                String subject = reference.getSubject();
                int guessedVersion = reference.getVersion();

                for (int version = guessedVersion - 1; version >= 1; version--) {
                    Schema older;
                    try {
                        older = fetcher.getByVersion(subject, version, true);
                    } catch (RuntimeException fetchFailure) {
                        break; // stop walking back through this subject's history
                    }
                    if (older == null || older.getSchema() == null) {
                        continue;
                    }

                    log.warn("Full model build failed for subject '{}' with naming-convention-"
                                    + "guessed dependency '{}' resolved at (globally latest) version "
                                    + "{}; retrying with older version {} instead, since a schema "
                                    + "being freshly registered (schema.getVersion() == null) can "
                                    + "never be reliably matched against the guessed dependency's own "
                                    + "reference list, and the registry may already hold later, "
                                    + "incompatible versions of it: {}",
                            schema.getSubject(), name, guessedVersion, version, describe(originalFailure));

                    Map<String, String> retryRefs = new LinkedHashMap<>(resolvedRefs);
                    retryRefs.put(name, older.getSchema());
                    Map<String, SchemaReference> retryDirectRefs = new LinkedHashMap<>(directRefs);
                    retryDirectRefs.put(name, new SchemaReference(name, subject, version));
                    Set<String> retryAutoResolvedNames = new HashSet<>(autoResolvedNames);

                    discoverAdditionalDependencies(new DependencySource(subject, version, older.getSchema()),
                            retryRefs, retryDirectRefs, retryAutoResolvedNames);
                    try {
                        EffectiveModelContext context = buildEffectiveModel(schema, retryRefs);
                        log.info("Recovered from a full-model build failure for subject '{}' by "
                                        + "downgrading naming-convention-guessed dependency '{}' from "
                                        + "version {} to {}",
                                schema.getSubject(), name, guessedVersion, version);
                        resolvedRefs.putAll(retryRefs);
                        directRefs.putAll(retryDirectRefs);
                        autoResolvedNames.addAll(retryAutoResolvedNames);
                        return context;
                    } catch (YangParserException retryFailure) {
                        log.debug("Retry with '{}' downgraded to version {} still failed for subject '{}': {}",
                                name, version, schema.getSubject(), describe(retryFailure));
                    }
                }
            }
            throw originalFailure;
        }
    }

    private void resolveBelongsToParentIfMissing(Schema schema, Map<String, String> resolvedRefs,
            Map<String, SchemaReference> directRefs, Set<String> autoResolvedNames) {
        String text = schema.getSchema();
        if (text == null) {
            return;
        }
        Matcher submoduleMatcher = SUBMODULE_PATTERN.matcher(text);
        if (!submoduleMatcher.find()) {
            return; // not a submodule - nothing to do
        }
        String submoduleName = submoduleMatcher.group(1);

        Matcher belongsToMatcher = BELONGS_TO_PATTERN.matcher(text);
        if (!belongsToMatcher.find()) {
            return; // malformed submodule without belongs-to; let real parser report it
        }
        String parentModuleName = belongsToMatcher.group(1);

        if (containsModuleDeclaration(resolvedRefs.values(), parentModuleName)) {
            return; // already supplied explicitly via schema.getReferences()
        }

        String subject = schema.getSubject();
        if (subject == null || !subject.endsWith(submoduleName)) {
            log.debug("Submodule '{}' (subject '{}') doesn't follow the '<prefix>.<moduleName>' "
                            + "naming convention; skipping auto-resolution of belongs-to parent module '{}'.",
                    submoduleName, subject, parentModuleName);
            return;
        }
        String candidateSubject = subject.substring(0, subject.length() - submoduleName.length()) + parentModuleName;

        SchemaVersionFetcher fetcher = schemaVersionFetcher();
        if (fetcher == null) {
            log.warn("No SchemaVersionFetcher configured; cannot auto-resolve belongs-to parent "
                            + "module '{}' for submodule '{}' (subject '{}')",
                    parentModuleName, submoduleName, subject);
            return;
        }

        try {
            Schema parentSchema = findBestParentVersion(fetcher, candidateSubject, schema);
            if (parentSchema == null) {
                // Last-resort fallback: the parent module is absent from registry
                String stub = synthesizeStubParentModule(text, parentModuleName);
                log.warn("Auto-resolution of belongs-to parent module '{}' for submodule '{}' "
                                + "failed: no schema found under guessed subject '{}'; falling back "
                                + "to a synthesized stub parent module so SOURCE_LINKAGE can succeed",
                        parentModuleName, submoduleName, candidateSubject);
                resolvedRefs.put(parentModuleName, stub);
                return;
            }
            log.info("Auto-resolved belongs-to parent module '{}' (version {}) for submodule '{}' "
                            + "via guessed subject '{}' (schema.getReferences() didn't declare it "
                            + "explicitly)",
                    parentModuleName, parentSchema.getVersion(), submoduleName, candidateSubject);
            resolvedRefs.put(parentModuleName, parentSchema.getSchema());
            directRefs.put(parentModuleName,
                    new SchemaReference(parentModuleName, parentSchema.getSubject(), parentSchema.getVersion()));
            autoResolvedNames.add(parentModuleName);

            mergeParentReferences(schema, parentSchema, resolvedRefs, directRefs, new HashSet<>(), autoResolvedNames);
        } catch (RuntimeException e) {
            log.warn("Auto-resolution of belongs-to parent module '{}' for submodule '{}' via "
                            + "guessed subject '{}' threw; falling back to the real parser error: {}",
                    parentModuleName, submoduleName, candidateSubject, describe(e));
        }
    }

    /**
     * Merges every resolvable reference declared by {@code parentSchema} into {@code resolvedRefs}
     * (recursively, since those references may themselves have further references), skipping:
     * <ul>
     *   <li>any reference pointing at {@code (schema.getSubject(), schema.getVersion())} -
     *   which is always already present as the main source and may not be independently fetchable;</li>
     *   <li>any reference whose name is already present in {@code resolvedRefs};</li>
     *   <li>any {@code (subject, version)} already visited, to tolerate reference cycles.</li>
     * </ul>
     * Unlike the built-in {@code resolveReferences()}, a single unresolvable reference here only
     * skips that one entry (with a warning) instead of aborting the whole merge.
     */
    private void mergeParentReferences(Schema schema, Schema parentSchema, Map<String, String> resolvedRefs,
            Map<String, SchemaReference> directRefs, Collection<String> visitedSubjectVersions,
            Set<String> autoResolvedNames) {
        List<SchemaReference> references = parentSchema.getReferences();
        if (references == null) {
            return;
        }
        SchemaVersionFetcher fetcher = schemaVersionFetcher();
        for (SchemaReference reference : references) {
            if (reference.getName() == null || reference.getSubject() == null || reference.getVersion() == null) {
                continue;
            }
            if (reference.getSubject().equals(schema.getSubject())
                    && reference.getVersion().equals(schema.getVersion())) {
                continue; // points back at the schema currently being parsed - already the main source
            }
            if (resolvedRefs.containsKey(reference.getName())) {
                continue;
            }
            String visitKey = reference.getSubject() + "@" + reference.getVersion();
            if (!visitedSubjectVersions.add(visitKey)) {
                continue; // reference cycle
            }

            Schema referencedSchema;
            try {
                referencedSchema = fetcher.getByVersion(reference.getSubject(), reference.getVersion(), true);
            } catch (RuntimeException e) {
                log.warn("Skipping parent's reference '{}' (subject '{}', version {}) while "
                                + "auto-resolving belongs-to parent for '{}': {}",
                        reference.getName(), reference.getSubject(), reference.getVersion(),
                        schema.getSubject(), describe(e));
                continue;
            }
            if (referencedSchema == null) {
                log.warn("Skipping parent's reference '{}' (subject '{}', version {}) while "
                                + "auto-resolving belongs-to parent for '{}': not found",
                        reference.getName(), reference.getSubject(), reference.getVersion(),
                        schema.getSubject());
                continue;
            }
            resolvedRefs.put(reference.getName(), referencedSchema.getSchema());
            directRefs.put(reference.getName(),
                    new SchemaReference(reference.getName(), reference.getSubject(), referencedSchema.getVersion()));

            mergeParentReferences(schema, referencedSchema, resolvedRefs, directRefs, visitedSubjectVersions,
                    autoResolvedNames);
        }
    }

    private void resolveMissingDependencies(Schema schema, Map<String, String> resolvedRefs,
            Map<String, SchemaReference> directRefs, Set<String> autoResolvedNames) {
        Deque<DependencySource> queue = new ArrayDeque<>();
        queue.add(new DependencySource(schema.getSubject(), schema.getVersion(), schema.getSchema()));
        for (Map.Entry<String, SchemaReference> entry : directRefs.entrySet()) {
            String text = resolvedRefs.get(entry.getKey());
            SchemaReference reference = entry.getValue();
            if (text != null && reference.getSubject() != null) {
                queue.add(new DependencySource(reference.getSubject(), reference.getVersion(), text));
            }
        }
        runDependencyDiscoveryBfs(queue, resolvedRefs, directRefs, autoResolvedNames);
    }

    private void discoverAdditionalDependencies(DependencySource seed, Map<String, String> resolvedRefs,
            Map<String, SchemaReference> directRefs, Set<String> autoResolvedNames) {
        Deque<DependencySource> queue = new ArrayDeque<>();
        queue.add(seed);
        runDependencyDiscoveryBfs(queue, resolvedRefs, directRefs, autoResolvedNames);
    }

    /**
     * Shared BFS core for {@link #resolveMissingDependencies} and
     * {@link #discoverAdditionalDependencies} - see {@link #resolveMissingDependencies}'s own
     * javadoc for the full rationale/shapes this handles.
     */
    private void runDependencyDiscoveryBfs(Deque<DependencySource> queue, Map<String, String> resolvedRefs,
            Map<String, SchemaReference> directRefs, Set<String> autoResolvedNames) {
        SchemaVersionFetcher fetcher = schemaVersionFetcher();
        if (fetcher == null) {
            return;
        }

        Set<String> declaredNames = new HashSet<>();
        for (String text : resolvedRefs.values()) {
            collectDeclaredName(text, declaredNames);
        }
        for (DependencySource seed : queue) {
            collectDeclaredName(seed.text, declaredNames);
        }

        Set<String> visitedSubjects = new HashSet<>();
        while (!queue.isEmpty()) {
            DependencySource current = queue.poll();
            String subject = current.subject;
            String text = current.text;
            if (subject == null || subject.isEmpty() || text == null || !visitedSubjects.add(subject)) {
                continue;
            }
            String[] ownDeclaration = extractDeclaration(text);
            if (ownDeclaration == null || !subject.endsWith(ownDeclaration[1])) {
                continue; // can't derive a naming-convention prefix from this particular source
            }
            String prefix = subject.substring(0, subject.length() - ownDeclaration[1].length());

            Matcher dependencyMatcher = DEPENDENCY_PATTERN.matcher(text);
            while (dependencyMatcher.find()) {
                String keyword = dependencyMatcher.group(1);
                String dependencyName = dependencyMatcher.group(2);
                if (declaredNames.contains(dependencyName)) {
                    continue; // already covered by some source we already have
                }

                String dependencySubject = prefix + dependencyName;
                Schema dependencySchema;
                try {
                    dependencySchema = "belongs-to".equals(keyword)
                            ? findBestVersionReferencing(fetcher, dependencySubject, subject, current.version)
                            : fetcher.getByVersion(dependencySubject, -1, true);
                } catch (RuntimeException e) {
                    log.debug("Auto-resolution of missing {} dependency '{}' (guessed subject "
                                    + "'{}') for '{}' threw; leaving it to the real parser error: {}",
                            keyword, dependencyName, dependencySubject, subject, describe(e));
                    continue;
                }
                if (dependencySchema == null) {
                    if ("belongs-to".equals(keyword)) {
                        log.warn("Auto-resolution of missing belongs-to dependency '{}' for '{}' "
                                        + "found nothing under guessed subject '{}'; falling back "
                                        + "to a synthesized stub parent module so SOURCE_LINKAGE "
                                        + "can succeed",
                                dependencyName, subject, dependencySubject);
                        String stub = synthesizeStubParentModule(text, dependencyName);
                        resolvedRefs.put(dependencyName, stub);
                        declaredNames.add(dependencyName);
                    } else {
                        log.debug("Auto-resolution of missing {} dependency '{}' for '{}' found "
                                        + "nothing under guessed subject '{}'",
                                keyword, dependencyName, subject, dependencySubject);
                    }
                    continue;
                }

                log.info("Auto-resolved missing {} dependency '{}' (version {}) required by '{}' "
                                + "via guessed subject '{}' (schema.getReferences() didn't declare "
                                + "it explicitly)",
                        keyword, dependencyName, dependencySchema.getVersion(), subject, dependencySubject);
                resolvedRefs.put(dependencyName, dependencySchema.getSchema());
                directRefs.put(dependencyName,
                        new SchemaReference(dependencyName, dependencySchema.getSubject(), dependencySchema.getVersion()));
                declaredNames.add(dependencyName);

                autoResolvedNames.add(dependencyName);
                collectDeclaredName(dependencySchema.getSchema(), declaredNames);
                queue.add(new DependencySource(dependencySubject, dependencySchema.getVersion(), dependencySchema.getSchema()));
            }
        }
    }

    private static String fixUnprefixedDeviationUniqueArguments(String text) {
        if (text == null || !text.contains("deviation") || !text.contains("unique")) {
            return text;
        }
        Matcher deviationMatcher = DEVIATION_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        while (deviationMatcher.find()) {
            int braceStart = deviationMatcher.end() - 1;
            int braceEnd = findMatchingBrace(text, braceStart);
            if (braceEnd < 0) {
                continue; // malformed/unbalanced braces - let the real parser report it
            }
            String targetPrefix = lastPathSegmentPrefix(deviationMatcher.group(1));
            if (targetPrefix == null) {
                continue; // path has no prefixed segment to derive a fallback prefix from
            }
            result.append(text, lastEnd, deviationMatcher.end());
            String body = text.substring(deviationMatcher.end(), braceEnd);
            result.append(prefixUnprefixedUniqueArguments(body, targetPrefix));
            lastEnd = braceEnd;
        }
        result.append(text, lastEnd, text.length());
        return result.toString();
    }

    private static int findMatchingBrace(String text, int openBraceIndex) {
        int depth = 0;
        for (int i = openBraceIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String lastPathSegmentPrefix(String path) {
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            int colon = segments[i].indexOf(':');
            if (colon > 0) {
                return segments[i].substring(0, colon);
            }
        }
        return null;
    }

    private static String prefixUnprefixedUniqueArguments(String deviationBody, String fallbackPrefix) {
        Matcher uniqueMatcher = UNIQUE_PATTERN.matcher(deviationBody);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        while (uniqueMatcher.find()) {
            result.append(deviationBody, lastEnd, uniqueMatcher.start());
            result.append(uniqueMatcher.group(1)).append('"');
            String[] components = uniqueMatcher.group(2).trim().split("\\s+");
            for (int i = 0; i < components.length; i++) {
                if (i > 0) {
                    result.append(' ');
                }
                result.append(prefixPathSegments(components[i], fallbackPrefix));
            }
            result.append('"').append(uniqueMatcher.group(3));
            lastEnd = uniqueMatcher.end();
        }
        result.append(deviationBody, lastEnd, deviationBody.length());
        return result.toString();
    }

    private static String prefixPathSegments(String component, String fallbackPrefix) {
        String[] segments = component.split("/");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                result.append('/');
            }
            String segment = segments[i];
            if (segment.isEmpty() || segment.indexOf(':') >= 0) {
                result.append(segment);
            } else {
                result.append(fallbackPrefix).append(':').append(segment);
            }
        }
        return result.toString();
    }

    private record DependencySource(String subject, Integer version, String text) {}

    private static void collectDeclaredName(String text, Set<String> out) {
        String[] declaration = extractDeclaration(text);
        if (declaration != null) {
            out.add(declaration[1]);
        }
    }

    private Schema findBestParentVersion(SchemaVersionFetcher fetcher, String candidateSubject, Schema schema) {
        return findBestVersionReferencing(fetcher, candidateSubject, schema.getSubject(), schema.getVersion());
    }

    private Schema findBestVersionReferencing(SchemaVersionFetcher fetcher, String candidateSubject,
            String referencingSubject, Integer referencingVersion) {
        Schema latest = fetcher.getByVersion(candidateSubject, -1, true);
        if (latest == null) {
            return null;
        }
        if (referencesSubjectVersion(latest, referencingSubject, referencingVersion)) {
            return latest;
        }

        Integer latestVersion = latest.getVersion();
        if (latestVersion == null) {
            return latest; // can't walk backward without a concrete version number
        }

        for (int version = latestVersion - 1; version >= 1; version--) {
            Schema candidate;
            try {
                candidate = fetcher.getByVersion(candidateSubject, version, true);
            } catch (RuntimeException e) {
                log.debug("Stopped walking back through '{}' versions while looking for the "
                                + "version referencing {} v{}: {}",
                        candidateSubject, referencingSubject, referencingVersion, describe(e));
                break;
            }
            if (candidate != null && referencesSubjectVersion(candidate, referencingSubject, referencingVersion)) {
                log.debug("Found '{}' version {} explicitly referencing {} v{} (latest version {} "
                                + "does not)",
                        candidateSubject, version, referencingSubject, referencingVersion, latestVersion);
                return candidate;
            }
        }

        log.debug("No version of '{}' explicitly references {} v{}; falling back to the latest "
                        + "version {} as a best-effort guess.",
                candidateSubject, referencingSubject, referencingVersion, latestVersion);
        return latest;
    }

    private static boolean referencesSubjectVersion(Schema candidateParent, String subject, Integer version) {
        List<SchemaReference> references = candidateParent.getReferences();
        if (references == null || subject == null || version == null) {
            return false;
        }
        for (SchemaReference reference : references) {
            if (subject.equals(reference.getSubject()) && version.equals(reference.getVersion())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsModuleDeclaration(Collection<String> schemaTexts, String moduleName) {
        Pattern modulePattern = Pattern.compile("(?:^|\\s)module\\s+" + Pattern.quote(moduleName) + "\\s*\\{");
        for (String text : schemaTexts) {
            if (text != null && modulePattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes any entry from {@code resolvedRefs} whose schema text declares the exact same
     * {@code module}/{@code submodule} name as {@code schema} itself. {@code schema} is always
     * added as its own parser source under {@code schema.getSubject()} in {@link #parse}, so a
     * second source declaring the identical module/submodule name - no matter which resolution
     * path put it into {@code resolvedRefs} - is guaranteed to fail ODL's "Submodule name
     * collision" (or the module-name equivalent) check.
     */
    private void removeSelfReferenceCollisions(Schema schema, Map<String, String> resolvedRefs) {
        String[] ownDeclaration = extractDeclaration(schema.getSchema());
        if (ownDeclaration == null) {
            return;
        }
        String ownName = ownDeclaration[1];

        Iterator<Map.Entry<String, String>> it = resolvedRefs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> entry = it.next();
            String[] candidateDeclaration = extractDeclaration(entry.getValue());
            if (candidateDeclaration != null && ownName.equals(candidateDeclaration[1])) {
                log.debug("Dropping resolved reference '{}' for subject '{}': it declares the same "
                                + "'{}' name as the schema being parsed - it's already present as the "
                                + "main source, so keeping it too would trip a name-collision error.",
                        entry.getKey(), schema.getSubject(), ownName);
                it.remove();
            }
        }
    }

    /**
     * @return a {@code [keyword, name]} pair (keyword is {@code "module"} or {@code "submodule"})
     *     for the first top-level {@code module}/{@code submodule} declaration found in
     *     {@code text}, or {@code null} if none is found.
     */
    private static String[] extractDeclaration(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = OWN_DECLARATION_PATTERN.matcher(text);
        return matcher.find() ? new String[] {matcher.group(1), matcher.group(2)} : null;
    }

    /**
     * Builds a human-readable description of a (possibly nested/multi-cause) parser failure so the
     * reason is visible in the 422 response body.
     */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 10) {
            if (depth > 0) {
                sb.append(" -- caused by: ");
            }
            if (current instanceof YangSyntaxErrorException syntaxError) {
                sb.append(syntaxError.getFormattedMessage());
            } else {
                sb.append(current.getClass().getSimpleName());
                if (current.getMessage() != null) {
                    sb.append(": ").append(current.getMessage());
                }
            }
            for (Throwable suppressed : current.getSuppressed()) {
                sb.append(" [also: ").append(describe(suppressed)).append(']');
            }
            Throwable next = current.getCause();
            current = (next == current) ? null : next;
            depth++;
        }
        return sb.toString();
    }

    private static String synthesizeStubParentModule(String submoduleText, String parentModuleName) {
        String prefix = parentModuleName;
        Matcher belongsToMatcher = BELONGS_TO_PATTERN.matcher(submoduleText);
        if (belongsToMatcher.find()) {
            int braceStart = belongsToMatcher.end() - 1;
            int braceEnd = findMatchingBrace(submoduleText, braceStart);
            String body = braceEnd >= 0
                    ? submoduleText.substring(braceStart, braceEnd)
                    : submoduleText.substring(braceStart);
            Matcher prefixMatcher = PREFIX_PATTERN.matcher(body);
            if (prefixMatcher.find()) {
                prefix = prefixMatcher.group(1);
            }
        }
        return "module " + parentModuleName + " {\n"
                + "  yang-version 1.1;\n"
                + "  namespace \"urn:stub:" + parentModuleName + "\";\n"
                + "  prefix " + prefix + ";\n"
                + "}\n";
    }
}
