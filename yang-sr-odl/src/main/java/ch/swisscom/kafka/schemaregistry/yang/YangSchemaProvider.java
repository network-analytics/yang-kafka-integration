package ch.swisscom.kafka.schemaregistry.yang;

import ch.swisscom.kafka.schemaregistry.util.ParseErrorReason;
import ch.swisscom.kafka.schemaregistry.util.YangSchemaProviderMetrics;
import io.confluent.kafka.schemaregistry.AbstractSchemaProvider;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
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
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class YangSchemaProvider extends AbstractSchemaProvider {
    private static final Logger log = LoggerFactory.getLogger(YangSchemaProvider.class);

    private static final Pattern OWN_DECLARATION_PATTERN = Pattern.compile("\\b(module|submodule)\\s+([\\w.-]+)\\s*\\{");
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
        try {
            resolvedRefs = resolveReferences(schema);
        } catch (Exception e) {
            log.error("Failed to resolve references for subject {}: {}", schema.getSubject(), describe(e), e);
            metrics.recordParseError(ParseErrorReason.UNRESOLVABLE_REFERENCE);
            throw new RuntimeException(
                    "Failed to resolve schema references for subject " + schema.getSubject()
                            + ": " + describe(e), e);
        }

        removeSelfReferenceCollisions(schema, resolvedRefs);

        EffectiveModelContext context;
        try {
            context = buildEffectiveModel(schema, resolvedRefs);
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
        return parser.buildEffectiveModel();
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

    private static String[] extractDeclaration(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = OWN_DECLARATION_PATTERN.matcher(text);
        return matcher.find() ? new String[] {matcher.group(1), matcher.group(2)} : null;
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
}
