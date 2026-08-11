package ch.swisscom.kafka.schemaregistry.yang;

import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.rest.entities.Metadata;
import io.confluent.kafka.schemaregistry.client.rest.entities.RuleSet;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaEntity;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class YangSchema implements ParsedSchema {
    public static final String TYPE = "YANG";

    private final String schemaString;
    private final EffectiveModelContext context;
    private final List<SchemaReference> references;
    private final Metadata metadata;
    private final Integer version;
    private final RuleSet ruleSet;

    public YangSchema(
        String schemaString,
        Integer version,
        EffectiveModelContext context,
        List<SchemaReference> references,
        Metadata metadata,
        RuleSet ruleSet
    ) {
        this.schemaString = schemaString;
        this.version = version;
        this.context = context;
        this.references = references == null ? Collections.emptyList() : Collections.unmodifiableList(references);
        this.metadata = metadata;
        this.ruleSet = ruleSet;
    }

    @Override
    public String schemaType() {
        return TYPE;
    }

    @Override
    public String name() {
        // Find the main module name, simplified version
        if (context != null && !context.getModules().isEmpty()) {
            return context.getModules().iterator().next().getName();
        }
        return "unknown";
    }

    @Override
    public String canonicalString() {
        return schemaString;
    }

    @Override
    public Integer version() {
        return version;
    }

    @Override
    public List<SchemaReference> references() {
        return references;
    }

    @Override
    public Metadata metadata() {
        return metadata;
    }

    @Override
    public RuleSet ruleSet() {
        return ruleSet;
    }

    @Override
    public ParsedSchema copy() {
        return new YangSchema(schemaString, version, context, references, metadata, ruleSet);
    }

    @Override
    public ParsedSchema copy(Integer version) {
        return new YangSchema(schemaString, version, context, references, metadata, ruleSet);
    }

    @Override
    public ParsedSchema copy(Metadata metadata, RuleSet ruleSet) {
        return new YangSchema(schemaString, version, context, references, metadata, ruleSet);
    }

    @Override
    public List<String> isBackwardCompatible(ParsedSchema previousSchema) {
        // TODO: Placeholder for compatible checks. ODL Yangtools has built in, but needs rule-based comparison.
        return Collections.emptyList();
    }

    @Override
    public Object rawSchema() {
        return context;
    }

    @Override
    public ParsedSchema copy(Map<SchemaEntity, Set<String>> tagsToAdd, Map<SchemaEntity, Set<String>> tagsToRemove) {
        throw new UnsupportedOperationException("Tag modifications are not implemented");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        YangSchema that = (YangSchema) o;
        return Objects.equals(schemaString, that.schemaString) &&
                Objects.equals(references, that.references) &&
                Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaString, references, version);
    }
}

