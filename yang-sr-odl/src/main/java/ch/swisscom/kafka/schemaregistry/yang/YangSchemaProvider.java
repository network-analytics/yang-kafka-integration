package ch.swisscom.kafka.schemaregistry.yang;

import ch.swisscom.kafka.schemaregistry.util.ParseErrorReason;
import ch.swisscom.kafka.schemaregistry.util.YangSchemaProviderMetrics;
import ch.swisscom.kafka.schemaregistry.yang.preprocessing.YangSourcePreprocessor;
import io.confluent.kafka.schemaregistry.AbstractSchemaProvider;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.parser.api.YangParserConfiguration;
import org.opendaylight.yangtools.yang.parser.api.YangParserException;
import org.opendaylight.yangtools.yang.parser.api.YangSyntaxErrorException;
import org.opendaylight.yangtools.yang.model.api.source.SourceIdentifier;
import org.opendaylight.yangtools.yang.model.api.source.SourceSyntaxException;
import org.opendaylight.yangtools.yang.model.api.source.YangSourceRepresentation;
import org.opendaylight.yangtools.yang.model.spi.source.SourceInfo;
import org.opendaylight.yangtools.yang.model.spi.source.StringYangTextSource;
import org.opendaylight.yangtools.yang.model.spi.source.YangIRSource;
import org.opendaylight.yangtools.yang.model.spi.source.YangTextToIRSourceTransformer;
import org.opendaylight.yangtools.yang.parser.api.YangParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;


public class YangSchemaProvider extends AbstractSchemaProvider {
    private static final Logger log = LoggerFactory.getLogger(YangSchemaProvider.class);

    private final YangParserFactory factory = ServiceLoader.load(YangParserFactory.class).findFirst().orElseThrow();
    private final YangTextToIRSourceTransformer textToIr =
            ServiceLoader.load(YangTextToIRSourceTransformer.class).findFirst().orElseThrow();
    private final YangSchemaProviderMetrics metrics;
    private final YangSourcePreprocessor yangSourcePreprocessor;

    public YangSchemaProvider() {
        this(YangSourcePreprocessor.standard());
    }

    YangSchemaProvider(final YangSourcePreprocessor yangSourcePreprocessor) {
        this.metrics = new YangSchemaProviderMetrics();
        this.yangSourcePreprocessor = yangSourcePreprocessor;
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
            throw new RuntimeException("Failed to parse YANG schema " + schema.getSubject() + ": " + describe(e), e);
        }
    }

    private YangSchema parse(Schema schema) throws IOException {
        ParsedSource main = parseSource(schema.getSubject(), schema.getSchema());
        if (main.info() instanceof SourceInfo.Submodule) {
            return new YangSchema(
                schema.getSchema(),
                main.name(),
                schema.getVersion(),
                null,
                schema.getReferences(),
                schema.getMetadata(),
                schema.getRuleSet()
            );
        }

        Map<String, String> resolvedRefs;
        try {
            resolvedRefs = resolveReferences(schema);
        } catch (Exception e) {
            log.error("Failed to resolve references for subject {}: {}", schema.getSubject(), describe(e), e);
            metrics.recordParseError(ParseErrorReason.UNRESOLVABLE_REFERENCE);
            throw new RuntimeException(
                    "Failed to resolve schema references for subject " + schema.getSubject() + ": " + describe(e), e);
        }

        BuiltModel built;
        try {
            built = buildEffectiveModel(main, schema, resolvedRefs);
        } catch (YangParserException e) {
            log.error("Error parsing YANG schema for subject {}: {}", schema.getSubject(), describe(e), e);
            metrics.recordParseError(ParseErrorReason.PARSE_SCHEMA);
            throw new RuntimeException("Invalid YANG schema, subject: " + schema.getSubject() + ": " + describe(e), e);
        }

        return new YangSchema(schema.getSchema(), built.mainName(), schema.getVersion(), built.context(),
                schema.getReferences(), schema.getMetadata(), schema.getRuleSet());
    }

    private record BuiltModel(EffectiveModelContext context, String mainName) {
    }

    /**
     * Builds a fresh YangParser, adds main schema + every entry in resolvedRefs as sources and builds effective model.
     */
    private BuiltModel buildEffectiveModel(ParsedSource main, Schema schema, Map<String, String> resolvedRefs)
            throws YangParserException, IOException {
        var parser = factory.createParser(YangParserConfiguration.DEFAULT);

        String mainName = main.name();

        var sources = new ArrayList<ParsedSource>();
        for (Map.Entry<String, String> entry : resolvedRefs.entrySet()) {
            ParsedSource ref = parseSource(entry.getKey(), entry.getValue());
            if (mainName != null && mainName.equals(ref.name())) {
                log.debug("Skipping reference {} - it is another version of {}", entry.getKey(), mainName);
                continue;
            }
            sources.add(ref);
        }

        var irSources = new ArrayList<YangIRSource>();
        var textSources = new ArrayList<YangSourceRepresentation>();
        YangIRSource root = main.source() instanceof YangIRSource ir ? ir : null;
        for (ParsedSource source : concat(main, sources)) {
            if (source.source() instanceof YangIRSource ir) {
                irSources.add(ir);
            } else {
                textSources.add(source.source());
            }
        }

        // pre-processing step before going to yangtools' parser.
        for (YangSourceRepresentation source : textSources) {
            parser.addSource(source);
        }
        // The root is passed on so rules can tell what is being registered (e.g. a submodule needing a stub parent)
        for (YangIRSource source : yangSourcePreprocessor.prepare(schema.getSubject(), root, irSources)) {
            parser.addSource(source);
        }

        // NOTE: this single call both resolves imports/augments/deviations/groupings against
        // every added source (main schema + all references above) AND fully semantically
        // validates the resulting model
        return new BuiltModel(parser.buildEffectiveModel(), mainName);
    }

    /**
     * A source as handed to the parser. {@code info} is {@code null} when the text cannot be parsed - the parser is
     * then given the plain text so that it reports the syntax error with line and column.
     */
    private record ParsedSource(YangSourceRepresentation source, SourceInfo info) {
        String name() {
            return info == null ? null : info.sourceId().name().getLocalName();
        }
    }

    private static List<ParsedSource> concat(ParsedSource main, List<ParsedSource> refs) {
        var all = new ArrayList<ParsedSource>(refs.size() + 1);
        all.add(main);
        all.addAll(refs);
        return all;
    }

    private ParsedSource parseSource(String label, String text) throws IOException {
        var textSource = new StringYangTextSource(toSourceId(label), text);
        try {
            YangIRSource ir = textToIr.transformSource(textSource);
            SourceInfo info = ir.extractSourceInfo();
            return new ParsedSource(ir.withSourceId(info.sourceId()), info);
        } catch (SourceSyntaxException e) {
            return new ParsedSource(textSource, null);
        }
    }

    /**
     * Subjects and reference names are arbitrary strings, while a {@link SourceIdentifier} name must be a valid YANG
     * identifier. It is only used to point at the source in error messages, so it is sanitized.
     */
    private static SourceIdentifier toSourceId(String label) {
        String id = label.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (id.isEmpty() || !(Character.isLetter(id.charAt(0)) || id.charAt(0) == '_')) {
            id = "_" + id;
        }
        return new SourceIdentifier(id);
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
