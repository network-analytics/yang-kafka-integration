package ch.swisscom.kafka.schemaregistry.yang;

import ch.swisscom.kafka.schemaregistry.yang.compatibility.YangCompatibilityChecker;
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
    private final String name;
    private final EffectiveModelContext context;
    private final List<SchemaReference> references;
    private final Metadata metadata;
    private final Integer version;
    private final RuleSet ruleSet;
    private final YangCompatibilityChecker compatibilityChecker;

    public YangSchema(
        String schemaString,
        String name,
        Integer version,
        EffectiveModelContext context,
        List<SchemaReference> references,
        Metadata metadata,
        RuleSet ruleSet
    ) {
        this(schemaString, name, version, context, references, metadata, ruleSet, YangCompatibilityChecker.strict());
    }

    public YangSchema(
        String schemaString,
        String name,
        Integer version,
        EffectiveModelContext context,
        List<SchemaReference> references,
        Metadata metadata,
        RuleSet ruleSet,
        YangCompatibilityChecker compatibilityChecker
    ) {
        this.schemaString = schemaString;
        this.name = name;
        this.version = version;
        this.context = context;
        this.references = references == null ? Collections.emptyList() : Collections.unmodifiableList(references);
        this.metadata = metadata;
        this.ruleSet = ruleSet;
        this.compatibilityChecker = Objects.requireNonNull(compatibilityChecker, "compatibilityChecker");
    }

    @Override
    public String schemaType() {
        return TYPE;
    }

    @Override
    public String name() {
        if (name != null) {
            return name;
        }
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
        return new YangSchema(schemaString, name, version, context, references, metadata, ruleSet,
                compatibilityChecker);
    }

    @Override
    public ParsedSchema copy(Integer version) {
        return new YangSchema(schemaString, name, version, context, references, metadata, ruleSet,
                compatibilityChecker);
    }

    @Override
    public ParsedSchema copy(Metadata metadata, RuleSet ruleSet) {
        return new YangSchema(schemaString, name, version, context, references, metadata, ruleSet,
                compatibilityChecker);
    }

    /**
     * Checks this (the new) module against the previous one with the rules of RFC 7950 section 11. A submodule has no
     * effective model of its own, it is part of the module which includes it, so there is nothing to compare.
     */
    @Override
    public List<String> isBackwardCompatible(ParsedSchema previousSchema) {
        if (!(previousSchema instanceof YangSchema previous)) {
            return Collections.singletonList("Incompatible schema types");
        }
        if (context == null || previous.context == null) {
            return Collections.emptyList();
        }
        return compatibilityChecker.check(previous.context, previous.name(), context, name());
    }

    @Override
    public Object rawSchema() {
        return context;
    }

    @Override
    public ParsedSchema copy(Map<SchemaEntity, Set<String>> tagsToAdd, Map<SchemaEntity, Set<String>> tagsToRemove) {
        throw new UnsupportedOperationException("Tag modifications are not implemented");
    }

    /**
     * Same schema text, metadata and rule set, ignoring version and references. This is how the registry finds an
     * already registered schema when a new one without a version is posted. Confluent 7.x compares
     * {@link #rawSchema()} in {@code deepEquals}, which is a freshly built {@code EffectiveModelContext} per parse
     * and never equal, so the text is compared instead. Confluent 8.x calls {@code equivalent} (also from
     * {@code canLookup}); it has no {@code @Override} as 7.x does not have that method.
     * (SR already handles reference comparisons before equivalent is called, so no need to check it here.)
     */
    public boolean equivalent(ParsedSchema schema) {
        if (this == schema) {
            return true;
        }
        if (schema == null || getClass() != schema.getClass()) return false;
        return Objects.equals(canonicalString(), schema.canonicalString())
                && Objects.equals(metadata(), schema.metadata())
                && Objects.equals(ruleSet(), schema.ruleSet());
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean deepEquals(ParsedSchema schema) {
        return equivalent(schema);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        YangSchema that = (YangSchema) o;
        return Objects.equals(schemaString, that.schemaString) &&
                Objects.equals(references, that.references) &&
                Objects.equals(metadata, that.metadata) &&
                Objects.equals(ruleSet, that.ruleSet) &&
                Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaString, references, metadata, ruleSet, version);
    }
}

