package ch.swisscom.kafka.schemaregistry.yang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaString;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.yangcentral.yangkit.base.YangElement;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.model.api.stmt.Module;
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

  static final String Ref2YangSchema =
      "module ref2 {\n"
          + "  yang-version \"1.1\";\n"
          + "  namespace \"urn:example:schema:test:ref2\";\n"
          + "  prefix \"ref2\";\n"
          + "  revision \"2023-02-07\";\n"
          + "  typedef bType {\n"
          + "    type \"int16\";\n"
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
    schemas.put("ref2", Ref2YangSchema);
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

  @Test(expected = YangParserException.class)
  public void testParseInvalidSchema() throws YangParserException {
    YangSchemaContext context = YangStatementRegister.getInstance().getSchemeContextInstance();
    YangSchemaUtils.parseYangString("a-module", "module a-module {", context);
  }

  @Test
  public void testParseYangStringReusesContext()
      throws YangParserException {
    YangSchemaContext context = YangStatementRegister.getInstance().getSchemeContextInstance();
    Map<String, String> schemas = getYangSchemaWithDependencies();

    YangSchemaUtils.parseYangString("ref", schemas.get("ref"), context);
    // parse a second reference, "independent" module and re-use the same context.
    YangSchemaUtils.parseYangString("ref2", schemas.get("ref2"), context);

    assertEquals(2, context.getModules().size());
    assertTrue("expected parse-result of 'ref2'", context.getParseResult().containsKey("ref2"));
    assertTrue("expected parse-result of 'ref'", context.getParseResult().containsKey("ref"));

    assertEquals("ref", moduleNameOf(context.getParseResult().get("ref")));
    assertEquals("ref2", moduleNameOf(context.getParseResult().get("ref2")));
  }

  private static String moduleNameOf(List<YangElement> elementList) {
    return elementList.stream()
        .filter(Module.class::isInstance)
        .map(Module.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no Module found in elementList"))
        .getModuleId()
        .getModuleName();
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
