/*
 * Copyright 2023 Swisscom (Schweiz) AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ch.swisscom.kafka.schemaregistry.yang;

import ch.swisscom.kafka.schemaregistry.util.YangSchemaProviderMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.rest.entities.Metadata;
import io.confluent.kafka.schemaregistry.client.rest.entities.RuleSet;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaEntity;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.XMLWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yangcentral.yangkit.common.api.exception.Severity;
import org.yangcentral.yangkit.common.api.validate.ValidatorResult;
import org.yangcentral.yangkit.common.api.validate.ValidatorResultBuilder;
import org.yangcentral.yangkit.comparator.CompareType;
import org.yangcentral.yangkit.comparator.CompatibilityRule;
import org.yangcentral.yangkit.comparator.YangComparator;
import org.yangcentral.yangkit.comparator.YangCompareResult;
import org.yangcentral.yangkit.data.api.model.YangDataDocument;
import org.yangcentral.yangkit.data.codec.json.YangDataDocumentJsonParser;
import org.yangcentral.yangkit.model.api.codec.YangCodecException;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.model.api.stmt.Module;

public class YangSchema implements ParsedSchema {
  private static final Logger log = LoggerFactory.getLogger(YangSchema.class);

  public static final String TYPE = "YANG";

  private final String schemaString;
  private final Module module;
  private final YangSchemaContext context;
  private final List<SchemaReference> references;
  private final Map<String, String> resolvedReferences;
  private final Metadata metadata;
  private final Integer version;
  private final RuleSet ruleSet;

  private static final int NO_HASHCODE = Integer.MIN_VALUE;
  private transient int hashCode = NO_HASHCODE;

  private final boolean skipCompatibilityCheck;

  private final YangSchemaProviderMetrics metrics;

  public YangSchema(
      String schemaString,
      Integer version,
      YangSchemaContext context,
      Module module,
      List<SchemaReference> references,
      Map<String, String> resolvedReferences,
      Metadata metadata,
      RuleSet ruleSet,
      boolean skipCompatibilityCheck,
      YangSchemaProviderMetrics metrics) {
    this.schemaString = schemaString;
    this.version = version;

    this.context = context;
    this.module = module;
    this.references = Collections.unmodifiableList(references);
    this.resolvedReferences = Collections.unmodifiableMap(resolvedReferences);
    this.metadata = metadata;
    this.ruleSet = ruleSet;
    this.skipCompatibilityCheck = skipCompatibilityCheck;
    this.metrics = metrics;
  }

  public YangSchema(
      String schemaString,
      YangSchemaContext context,
      Module module,
      List<SchemaReference> references,
      Map<String, String> resolvedReferences,
      boolean skipCompatibilityCheck,
      YangSchemaProviderMetrics metrics) {
    this(
        schemaString,
        null,
        context,
        module,
        references,
        resolvedReferences,
        null,
        null,
        skipCompatibilityCheck,
        metrics);
  }

  public YangSchema(
      String schemaString,
      YangSchemaContext context,
      Module module,
      List<SchemaReference> references,
      Map<String, String> resolvedReferences) {
    this(schemaString, null, context, module, references, resolvedReferences, null, null, false, null);
  }

  @Override
  public String schemaType() {
    return TYPE;
  }

  @Override
  public String name() {
    return this.module.getModuleId().getModuleName();
  }

  @Override
  public String canonicalString() {
    return this.schemaString;
  }

  @Override
  public Integer version() {
    return this.version;
  }

  @Override
  public List<SchemaReference> references() {
    return this.references;
  }

  @Override
  public Metadata metadata() {
    return this.metadata;
  }

  @Override
  public RuleSet ruleSet() {
    return this.ruleSet;
  }

  @Override
  public YangSchema copy() {
    return new YangSchema(
        this.schemaString,
        this.version,
        this.context,
        this.module,
        this.references,
        this.resolvedReferences,
        this.metadata,
        this.ruleSet,
        this.skipCompatibilityCheck,
        this.metrics);
  }

  @Override
  public YangSchema copy(Integer version) {
    return new YangSchema(
        this.schemaString,
        version,
        this.context,
        this.module,
        this.references,
        this.resolvedReferences,
        this.metadata,
        this.ruleSet,
        this.skipCompatibilityCheck,
        this.metrics);
  }

  @Override
  public YangSchema copy(Metadata metadata, RuleSet ruleSet) {
    return new YangSchema(
        this.schemaString,
        this.version,
        this.context,
        this.module,
        this.references,
        this.resolvedReferences,
        metadata,
        ruleSet,
        this.skipCompatibilityCheck,
        this.metrics);
  }

  @Override
  public ParsedSchema copy(
      Map<SchemaEntity, Set<String>> tagsToAdd, Map<SchemaEntity, Set<String>> tagsToRemove) {
    throw new IllegalArgumentException("Tag modifications is not implemented for YANG Schema");
  }

  public YangSchemaContext yangSchemaContext() {
    return this.context;
  }

  private Optional<String> writeDom4jDoc(org.dom4j.Document doc) {
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      OutputFormat format = OutputFormat.createPrettyPrint();
      XMLWriter writer = new XMLWriter(outputStream, format);
      writer.write(doc);
      writer.close();
      return Optional.of(outputStream.toString(Charset.defaultCharset()));
    } catch (IOException e) {
      log.error("Failed to write dom4j document", e);
      return Optional.empty();
    }
  }

  @Override
  public List<String> isBackwardCompatible(ParsedSchema previousSchema) {
    if (skipCompatibilityCheck) {
      log.debug("[hotfix] skipping backward compatibility check for {}", this.name());
      return Collections.emptyList();
    }
    log.debug("Checking if schema is backward compatible: {} and {}", this, previousSchema);
    if (!(previousSchema instanceof YangSchema)) {
      recordCompatibilityCheckFailure();
      return Collections.singletonList("Incompatible schema types");
    }
    YangSchema previousYangSchema = (YangSchema) previousSchema;
    YangComparator comparator =
        new YangComparator(previousYangSchema.yangSchemaContext(), this.context);

    try {
      CompareType compareType = CompareType.COMPATIBLE_CHECK;
      List<YangCompareResult> compareResults = comparator.compare(compareType, null);
      boolean nonBackwardCompatible =
          compareResults.stream()
              .map(x -> x.getCompatibilityInfo().getCompatibility())
              .anyMatch(x -> x == CompatibilityRule.Compatibility.NBC);
      List<String> ret = new ArrayList<>();
      if (nonBackwardCompatible) {
        recordCompatibilityCheckFailure();
        boolean needCompatible = true;
        var output =
            writeDom4jDoc(
                comparator.outputXmlCompareResult(compareResults, needCompatible, compareType));
        if (output.isPresent()) {
          ret.add(output.get());
        } else {
          ret.add("Incompatible schema changes detected, but failed to generate report.");
        }
      }
      return ret;
    } catch (Exception e) {
      log.error("Yang Schema Comparator exception", e);
      recordCompatibilityCheckFailure();
      return Collections.singletonList("Incompatible schema types");
    }
  }

  private void recordCompatibilityCheckFailure() {
    if (metrics != null) {
      metrics.recordCompatibilityCheckFailure(this.name());
    }
  }

  @Override
  public YangSchema normalize() {
    return this;
  }

  @Override
  public void validate() {
    ValidatorResult result = this.context.validate();
    if (!result.isOk()) {
      // YANGKit is not able to have complete validation context, this is only relevant for data
      // validation which is not performed by the schema registry.
      for (var record : result.getRecords()) {
        if (record.getSeverity().equals(Severity.ERROR)) {
          log.debug(
              "Invalid YANG validation context for module {}, ignored for now, {}",
              module.getModuleId().getModuleName(),
              record.getErrorMsg().getMessage());
        }
      }
    }
  }

  public YangDataDocument validate(JsonNode jsonNode) throws YangCodecException {
    return validate(context, jsonNode);
  }

  public static YangDataDocument validate(YangSchemaContext schemaContext, JsonNode jsonNode)
      throws YangCodecException {
    ValidatorResultBuilder validatorResultBuilder = new ValidatorResultBuilder();
    YangDataDocument yangDataDocument =
        new YangDataDocumentJsonParser(schemaContext).parse(jsonNode, validatorResultBuilder);
    yangDataDocument.update();
    ValidatorResult parseResult = validatorResultBuilder.build();

    if (!parseResult.isOk()) {
      throw new YangCodecException("YANG encoded message is not valid");
    }

    ValidatorResult validationResult = yangDataDocument.validate();

    if (!validationResult.isOk()) {
      throw new YangCodecException("YANG encoded message is not valid");
    }
    return yangDataDocument;
  }

  public YangDataDocument createYangDataDocument(JsonNode jsonNode) {
    return new YangDataDocumentJsonParser(context).parse(jsonNode, new ValidatorResultBuilder());
  }

  @Override
  public int hashCode() {
    if (hashCode == NO_HASHCODE) {
      hashCode =
          Objects.hash(
              this.schemaString,
              references,
              version(),
              metadata,
              ruleSet());
    }
    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    YangSchema other = (YangSchema) obj;
    return Objects.equals(this.schemaString, other.schemaString)
        && Objects.equals(this.references, other.references)
        && Objects.equals(this.version(), other.version())
        && Objects.equals(this.metadata, other.metadata)
        && Objects.equals(this.ruleSet(), other.ruleSet());
  }

  @Override
  public Module rawSchema() {
    return this.module;
  }
}
