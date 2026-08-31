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
import java.util.Map;


public class YangSchemaProvider extends AbstractSchemaProvider {
    private static final Logger log = LoggerFactory.getLogger(YangSchemaProvider.class);

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

        EffectiveModelContext context;
        try {
            context = buildEffectiveModel(schema, resolvedRefs);
        } catch (YangParserException e) {
            log.error("Error parsing YANG schema for subject {}: {}", schema.getSubject(), describe(e), e);
            metrics.recordParseError(ParseErrorReason.PARSE_SCHEMA);
            throw new RuntimeException("Invalid YANG schema, subject: " + schema.getSubject() + ": " + describe(e), e);
        }

        // Note: this is to debug number of YangStatements in the schema.
        recordStatementCounts(context);

        return new YangSchema(schema.getSchema(), schema.getVersion(), context, schema.getReferences(), null, null);
    }

    /**
     * Builds a fresh YangParser, adds main schema + every entry in resolvedRefs as sources and builds effective model.
     */
    private EffectiveModelContext buildEffectiveModel(Schema schema, Map<String, String> resolvedRefs)
            throws YangParserException, IOException {
        var parser = parserFactory.createParser(YangParserConfiguration.DEFAULT);

        parser.addSource(new StringYangTextSource(new SourceIdentifier(schema.getSubject()), schema.getSchema()));

        for (Map.Entry<String, String> entry : resolvedRefs.entrySet()) {
            parser.addSource(new StringYangTextSource(new SourceIdentifier(entry.getKey()), entry.getValue()));
        }
        // NOTE: this single call both resolves imports/augments/deviations/groupings against
        // every added source (main schema + all references above) AND fully semantically
        // validates the resulting model
        return parser.buildEffectiveModel();
    }

    private void recordStatementCounts(EffectiveModelContext context) {
        long schemaStatementCount = 0;
        for (var module : context.getModuleStatements().values()) {
            schemaStatementCount += YangSchemaUtils.countStatements(module);
        }
        metrics.recordSchemaStatementCount(schemaStatementCount);
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
