package ch.swisscom.kafka.schemaregistry.yang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaString;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.yangcentral.yangkit.model.api.codec.YangCodecException;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.parser.YangParserException;
import org.yangcentral.yangkit.register.YangStatementImplRegister;
import org.yangcentral.yangkit.register.YangStatementRegister;

public class YangSchemaUtilsTest {

  static final String RefYangSchema =
      "module ref {\n"
          + "  yang-version \"1.1\";\n"
          + "  namespace \"urn:example:schema:test:ref\";\n"
          + "  prefix \"ref\";\n"
          + "  revision \"2023-02-06\";\n"
          + "  typedef aType {\n"
          + "    type \"int8\";\n"
          + "  }\n"
          + "}\n";

  static final String RootYangSchema =
      "module root {\n"
          + "  yang-version \"1.1\";\n"
          + "  namespace \"urn:example:schema:test:root\";\n"
          + "  prefix \"root\";\n"
          + "  import ref {\n"
          + "    prefix \"ref\";\n"
          + "    revision-date \"2023-02-06\";\n"
          + "  }\n"
          + "  revision \"2023-02-06\";\n"
          + "  container root {\n"
          + "    leaf testLeaf {\n"
          + "      type \"ref:aType\";\n"
          + "      description\n"
          + "        \"Example leaf\";\n"
          + "    }\n"
          + "  }\n"
          + "}\n";

  public static Map<String, String> getYangSchemaWithDependencies() {
    Map<String, String> schemas = new HashMap<>();
    schemas.put("ref", RefYangSchema);
    schemas.put("root", RootYangSchema);
    return schemas;
  }

  @Before
  public void setUp() {
    YangStatementImplRegister.registerImpl();
  }

  @Test
  public void testParseValidSchema() throws YangParserException {
    YangSchemaContext context = YangStatementRegister.getInstance().getSchemeContextInstance();
    YangSchemaUtils.parseYangString("a-module", TestSchemas.SIMPLE_SCHEMA, context);
    assertEquals(1, context.getModules().size());
    assertTrue(context.getModule("a-module", "2023-02-01").isPresent());
  }

  @Test
  public void validateFilteredPushUpdateUsingMatchingSubscriptionFilter() throws Exception {
    YangSchemaContext context = createFilteredPushSchemaContext();
    String payload = filteredPushPayload(7, 7, "1");

    assertEquals(
        "/filtered-test:item/stats",
        YangSchema.getMatchingPushUpdateFilter(new ObjectMapper().readTree(payload)));
    assertTrue(YangSchema.validate(context, new ObjectMapper().readTree(payload)) != null);
  }

  @Test
  public void filteredPushUpdateWithMismatchedSubscriptionIdRemainsStrict() throws Exception {
    YangSchemaContext context = createFilteredPushSchemaContext();
    String payload = filteredPushPayload(7, 8, "1");

    try {
      YangSchema.validate(context, new ObjectMapper().readTree(payload));
      fail("A mismatched subscription ID must not activate filtered validation");
    } catch (YangCodecException expected) {
      assertTrue(expected.getMessage().contains("missing mandatory schema node"));
    }
  }

  @Test
  public void yangPatchEditValueIsValidatedAsSubtreeAtEditTarget() throws Exception {
    YangSchemaContext context = createYangPatchSchemaContext();

    assertTrue(
        YangSchema.validate(
                context,
                new ObjectMapper()
                    .readTree(yangPatchPayload("{\"patch-test:box\":{\"need\":\"x\"}}")))
            != null);
    try {
      YangSchema.validate(
          context, new ObjectMapper().readTree(yangPatchPayload("{\"patch-test:box\":{}}")));
      fail("Mandatory data inside the edit target must still be required");
    } catch (YangCodecException expected) {
      assertTrue(expected.getMessage().contains("missing mandatory schema node"));
    }
  }

  private String yangPatchPayload(String value) {
    return "{\"ietf-telemetry-message:message\":{\"payload\":{\"ietf-yp-notification:envelope\":"
        + "{\"contents\":{\"ietf-yang-push:push-change-update\":{\"datastore-changes\":"
        + "{\"yang-patch\":{\"edit\":[{\"edit-id\":\"e1\",\"operation\":\"merge\","
        + "\"target\":\"/patch-test:box\",\"value\":"
        + value
        + "}]}}}}}}}}";
  }

  private YangSchemaContext createYangPatchSchemaContext() throws YangParserException {
    YangSchemaContext context = YangStatementRegister.getInstance().getSchemeContextInstance();
    YangSchemaUtils.parseYangString(
        "ietf-telemetry-message",
        "module ietf-telemetry-message { yang-version 1.1; namespace urn:test:telemetry; "
            + "prefix tm; container message { anydata payload; } }",
        context);
    YangSchemaUtils.parseYangString(
        "ietf-yp-notification",
        "module ietf-yp-notification { yang-version 1.1; "
            + "namespace urn:test:yp-notification; prefix ypn; "
            + "container envelope { container contents; } }",
        context);
    YangSchemaUtils.parseYangString(
        "ietf-yang-push",
        "module ietf-yang-push { yang-version 1.1; "
            + "namespace urn:ietf:params:xml:ns:yang:ietf-yang-push; prefix yp; "
            + "import ietf-yp-notification { prefix ypn; } "
            + "augment /ypn:envelope/ypn:contents { container push-change-update { "
            + "container datastore-changes { container yang-patch { list edit { key edit-id; "
            + "leaf edit-id { type string; } leaf operation { type string; } "
            + "leaf target { type string; } anydata value; } } } } } }",
        context);
    YangSchemaUtils.parseYangString(
        "patch-test",
        "module patch-test { yang-version 1.1; namespace urn:test:patch; prefix pt; "
            + "container box { leaf need { type string; mandatory true; } } "
            + "container other { leaf required { type string; mandatory true; } } }",
        context);
    assertTrue(String.valueOf(context.validate().getRecords()), context.validate().isOk());
    return context;
  }

  private YangSchemaContext createFilteredPushSchemaContext() throws YangParserException {
    YangSchemaContext context = YangStatementRegister.getInstance().getSchemeContextInstance();
    YangSchemaUtils.parseYangString(
        "ietf-telemetry-message",
        "module ietf-telemetry-message { yang-version 1.1; namespace urn:test:telemetry; "
            + "prefix tm; container message { anydata telemetry-message-metadata; anydata payload; } }",
        context);
    YangSchemaUtils.parseYangString(
        "ietf-yang-push-telemetry-message",
        "module ietf-yang-push-telemetry-message { yang-version 1.1; "
            + "namespace urn:test:yang-push-telemetry-message; prefix yptm; "
            + "container yang-push-subscription { leaf id { type uint32; } "
            + "leaf xpath-filter { type string; } } }",
        context);
    YangSchemaUtils.parseYangString(
        "ietf-yp-notification",
        "module ietf-yp-notification { yang-version 1.1; "
            + "namespace urn:test:yp-notification; prefix ypn; "
            + "container envelope { container contents; } }",
        context);
    YangSchemaUtils.parseYangString(
        "ietf-yang-push",
        "module ietf-yang-push { yang-version 1.1; "
            + "namespace urn:ietf:params:xml:ns:yang:ietf-yang-push; prefix yp; "
            + "import ietf-yp-notification { prefix ypn; } "
            + "augment /ypn:envelope/ypn:contents { container push-update { "
            + "leaf id { type uint32; } anydata datastore-contents; } } }",
        context);
    YangSchemaUtils.parseYangString(
        "filtered-test",
        "module filtered-test { yang-version 1.1; namespace urn:test:filtered; prefix ft; "
            + "container item { leaf kind { type string; mandatory true; } "
            + "container stats { when \"../kind = 'enabled'\"; "
            + "leaf value { type uint8; mandatory true; } } } }",
        context);
    YangSchemaUtils.parseYangString(
        "filtered-augment",
        "module filtered-augment { yang-version 1.1; namespace urn:test:filtered-augment; "
            + "prefix fa; import filtered-test { prefix ft; } "
            + "augment /ft:item { leaf required-extension { type string; mandatory true; } } }",
        context);
    assertTrue(String.valueOf(context.validate().getRecords()), context.validate().isOk());
    return context;
  }

  private String filteredPushPayload(int subscriptionId, int updateId, String value) {
    return "{\"ietf-telemetry-message:message\":{"
        + "\"telemetry-message-metadata\":{"
        + "\"ietf-yang-push-telemetry-message:yang-push-subscription\":{"
        + "\"id\":"
        + subscriptionId
        + ",\"xpath-filter\":\"/filtered-test:item/stats\"}},"
        + "\"payload\":{\"ietf-yp-notification:envelope\":{\"contents\":{"
        + "\"ietf-yang-push:push-update\":{\"id\":"
        + updateId
        + ",\"datastore-contents\":{\"filtered-test:item\":{\"stats\":{\"value\":"
        + value
        + "}}}}}}}}}";
  }

  @Test(expected = YangParserException.class)
  public void testParseInvalidSchema() throws YangParserException {
    YangSchemaContext context = YangStatementRegister.getInstance().getSchemeContextInstance();
    YangSchemaUtils.parseYangString("a-module", "module a-module {", context);
  }

  @Test
  public void testHashSchema() {
    Schema schema1 = new Schema("subjectA", 1, 1, new SchemaString(TestSchemas.SIMPLE_SCHEMA));
    Schema schema2 = new Schema("subjectA", 1, 1, new SchemaString(TestSchemas.SIMPLE_SCHEMA));
    Schema schema3 =
        new Schema("subjectA", 1, 1, new SchemaString(TestSchemas.SIMPLE_SCHEMA_REORDERED));

    assertEquals(schema1.hashCode(), schema2.hashCode());
    assertNotEquals(schema1.hashCode(), schema3.hashCode());
  }
}
