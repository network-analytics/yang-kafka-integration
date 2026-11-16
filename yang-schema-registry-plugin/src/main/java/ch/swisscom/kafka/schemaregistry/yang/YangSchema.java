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
import java.util.stream.Collectors;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.XMLWriter;
import org.jaxen.JaxenException;
import org.jaxen.expr.LocationPath;
import org.jaxen.expr.NameStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yangcentral.yangkit.common.api.QName;
import org.yangcentral.yangkit.common.api.validate.ValidatorResult;
import org.yangcentral.yangkit.common.api.validate.ValidatorResultBuilder;
import org.yangcentral.yangkit.comparator.CompareType;
import org.yangcentral.yangkit.comparator.CompatibilityRule;
import org.yangcentral.yangkit.comparator.YangComparator;
import org.yangcentral.yangkit.comparator.YangCompareResult;
import org.yangcentral.yangkit.data.api.model.AnyDataData;
import org.yangcentral.yangkit.data.api.model.TypedData;
import org.yangcentral.yangkit.data.api.model.YangData;
import org.yangcentral.yangkit.data.api.model.YangDataContainer;
import org.yangcentral.yangkit.data.api.model.YangDataDocument;
import org.yangcentral.yangkit.data.codec.json.YangDataDocumentJsonParser;
import org.yangcentral.yangkit.model.api.codec.YangCodecException;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.model.api.stmt.Module;
import org.yangcentral.yangkit.xpath.impl.YangXPathImpl;

public class YangSchema implements ParsedSchema {
  private static final Logger log = LoggerFactory.getLogger(YangSchema.class);
  private static final String YANG_PATCH_NAMESPACE = "urn:ietf:params:xml:ns:yang:ietf-yang-patch";
  private static final String YANG_PUSH_NAMESPACE = "urn:ietf:params:xml:ns:yang:ietf-yang-push";

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

  public YangSchema(
      String schemaString,
      Integer version,
      YangSchemaContext context,
      Module module,
      List<SchemaReference> references,
      Map<String, String> resolvedReferences,
      Metadata metadata,
      RuleSet ruleSet) {
    this.schemaString = schemaString;
    this.version = version;

    this.context = context;
    this.module = module;
    this.references = Collections.unmodifiableList(references);
    this.resolvedReferences = Collections.unmodifiableMap(resolvedReferences);
    this.metadata = metadata;
    this.ruleSet = ruleSet;
  }

  public YangSchema(
      String schemaString,
      YangSchemaContext context,
      Module module,
      List<SchemaReference> references,
      Map<String, String> resolvedReferences) {
    this(schemaString, null, context, module, references, resolvedReferences, null, null);
  }

  public Map<String, String> resolvedReferences() {
    return this.resolvedReferences;
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
        this.ruleSet);
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
        this.ruleSet);
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
        ruleSet);
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
    log.debug("Checking if schema is backward compatible: {} and {}", this, previousSchema);
    if (!(previousSchema instanceof YangSchema)) {
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
      return Collections.singletonList("Incompatible schema types");
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
      throw new IllegalArgumentException(
          "Invalid YANG schema:\n"
              + result.getRecords().stream()
                  .map(Object::toString)
                  .collect(Collectors.joining("\n")));
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
    configureFilteredPushValidation(yangDataDocument, jsonNode);
    yangDataDocument.update();
    ValidatorResult parseResult = validatorResultBuilder.build();

    if (!parseResult.isOk()) {
      throw new YangCodecException(
          "YANG encoded message is not valid (parse errors):\n"
              + parseResult.getRecords().stream()
                  .map(Object::toString)
                  .collect(Collectors.joining("\n")));
    }

    ValidatorResult validationResult = yangDataDocument.validate();

    if (!validationResult.isOk()) {
      throw new YangCodecException(
          "YANG encoded message is not valid (validation errors):\n"
              + validationResult.getRecords().stream()
                  .map(Object::toString)
                  .collect(Collectors.joining("\n")));
    }
    return yangDataDocument;
  }

  public static void configureFilteredPushValidation(YangDataDocument document, JsonNode jsonNode) {
    if (document == null) {
      return;
    }
    String partialDataFilter = getMatchingPushUpdateFilter(jsonNode);
    markDatastoreContentsPartial(document, partialDataFilter);
  }

  static String getMatchingPushUpdateFilter(JsonNode jsonNode) {
    JsonNode message = jsonNode.path("ietf-telemetry-message:message");
    JsonNode subscription =
        message
            .path("telemetry-message-metadata")
            .path("ietf-yang-push-telemetry-message:yang-push-subscription");
    JsonNode pushUpdate =
        message
            .path("payload")
            .path("ietf-yp-notification:envelope")
            .path("contents")
            .path("ietf-yang-push:push-update");
    JsonNode subscriptionId = subscription.path("id");
    JsonNode updateId = pushUpdate.path("id");
    String filter = subscription.path("xpath-filter").asText("");
    if (!subscriptionId.isIntegralNumber()
        || !updateId.isIntegralNumber()
        || subscriptionId.asLong() != updateId.asLong()
        || !pushUpdate.path("datastore-contents").isObject()
        || !isAbsoluteChildLocationPath(filter)) {
      return null;
    }
    return filter;
  }

  private static boolean isAbsoluteChildLocationPath(String filter) {
    try {
      Object expression = new YangXPathImpl(filter).getRootExpr();
      if (!(expression instanceof LocationPath) || !((LocationPath) expression).isAbsolute()) {
        return false;
      }
      for (Object step : ((LocationPath) expression).getSteps()) {
        if (!(step instanceof NameStep)
            || ((NameStep) step).getAxis() != org.jaxen.saxpath.Axis.CHILD) {
          return false;
        }
      }
      return true;
    } catch (JaxenException e) {
      return false;
    }
  }

  private static void markDatastoreContentsPartial(YangDataContainer container, String filter) {
    QName parentName = getContainerQName(container);
    for (YangData<?> child : container.getDataChildren()) {
      if (child instanceof AnyDataData) {
        YangDataDocument value = ((AnyDataData) child).getValue();
        if (value == null) {
          continue;
        }
        QName childName = child.getQName();
        if (isYangPatchValue(parentName, childName)) {
          // RFC 8072: edit value is a subtree at the edit target, not a full datastore
          String targetFilter = getEditTargetFilter(container);
          if (targetFilter != null) {
            value.setPartialDataFilter(targetFilter);
          }
        } else if (filter != null
            && parentName != null
            && "push-update".equals(parentName.getLocalName())
            && YANG_PUSH_NAMESPACE.equals(parentName.getNamespace().toString())
            && "datastore-contents".equals(childName.getLocalName())
            && YANG_PUSH_NAMESPACE.equals(childName.getNamespace().toString())) {
          value.setPartialDataFilter(filter);
        } else {
          markDatastoreContentsPartial(value, filter);
        }
      } else if (child instanceof YangDataContainer) {
        markDatastoreContentsPartial((YangDataContainer) child, filter);
      }
    }
  }

  private static boolean isYangPatchValue(QName parentName, QName childName) {
    return parentName != null
        && "edit".equals(parentName.getLocalName())
        && isYangPatchNamespace(parentName)
        && "value".equals(childName.getLocalName())
        && isYangPatchNamespace(childName);
  }

  // yang-patch nodes are in the ietf-yang-push namespace when pulled in via uses
  private static boolean isYangPatchNamespace(QName name) {
    String namespace = name.getNamespace().toString();
    return YANG_PATCH_NAMESPACE.equals(namespace) || YANG_PUSH_NAMESPACE.equals(namespace);
  }

  // builds a child-path filter from the edit target by dropping list key predicates
  private static String getEditTargetFilter(YangDataContainer edit) {
    for (YangData<?> sibling : edit.getDataChildren()) {
      if (sibling instanceof TypedData
          && "target".equals(sibling.getQName().getLocalName())
          && isYangPatchNamespace(sibling.getQName())) {
        String target = ((TypedData<?, ?>) sibling).getStringValue();
        if (target == null) {
          return null;
        }
        String filter = target.replaceAll("\\[[^\\]]*\\]", "");
        return isAbsoluteChildLocationPath(filter) ? filter : null;
      }
    }
    return null;
  }

  private static QName getContainerQName(YangDataContainer container) {
    if (container instanceof YangDataDocument) {
      return ((YangDataDocument) container).getQName();
    }
    if (container instanceof YangData) {
      return ((YangData<?>) container).getQName();
    }
    return null;
  }

  public YangDataDocument createYangDataDocument(JsonNode jsonNode) {
    return new YangDataDocumentJsonParser(context).parse(jsonNode, new ValidatorResultBuilder());
  }

  @Override
  public int hashCode() {
    if (hashCode == NO_HASHCODE) {
      hashCode =
          Objects.hash(
              this.module.getModuleId().getModuleName(),
              this.module.getModuleId().getRevision(),
              this.module.getSubElements(),
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
    return Objects.equals(
            this.module.getModuleId().getModuleName(), other.module.getModuleId().getModuleName())
        && Objects.equals(
            this.module.getModuleId().getRevision(), other.module.getModuleId().getRevision())
        && Objects.equals(this.module.getSubElements(), other.module.getSubElements())
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
