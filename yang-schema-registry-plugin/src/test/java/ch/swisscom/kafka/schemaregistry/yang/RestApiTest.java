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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.confluent.kafka.schemaregistry.ClusterTestHarness;
import io.confluent.kafka.schemaregistry.CompatibilityLevel;
import io.confluent.kafka.schemaregistry.client.rest.RestService;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaString;
import io.confluent.kafka.schemaregistry.client.rest.entities.requests.RegisterSchemaRequest;
import io.confluent.kafka.schemaregistry.client.rest.entities.requests.RegisterSchemaResponse;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.rest.exceptions.Errors;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.model.api.stmt.Module;
import org.yangcentral.yangkit.register.YangStatementRegister;

public class RestApiTest extends ClusterTestHarness {

  private static final Random random = new Random();

  public RestApiTest() {
    super(DEFAULT_NUM_BROKERS, true);
  }

  @Override
  protected Properties getSchemaRegistryProperties() {
    Properties props = new Properties();
    props.setProperty("schema.providers", YangSchemaProvider.class.getName());
    return props;
  }

  public static List<String> getRandomYangSchemas(int num) {
    List<String> schemas = new ArrayList<>();
    for (int i = 0; i < num; i++) {
      int index = random.nextInt(Integer.MAX_VALUE);
      String schema =
          "module a"
              + index
              + " {\n"
              + "  yang-version \"1.1\";\n"
              + "  namespace \"urn:example:a\";\n"
              + "  prefix \"a"
              + index
              + "\";\n"
              + "  revision \"2023-02-06\";\n"
              + "  typedef aType {\n"
              + "    type \"int8\";\n"
              + "  }\n"
              + "  container x {\n"
              + "    leaf aLeaf {\n"
              + "      type aType;\n"
              + "      description\n"
              + "        \"Example leaf\";\n"
              + "    }\n"
              + "  }\n"
              + "}";
      schemas.add(schema);
    }
    return schemas;
  }

  public static void registerAndVerifySchema(
      RestService restService, String schemaString, int expectedId, String subject)
      throws IOException, RestClientException {
    registerAndVerifySchema(
        restService, schemaString, Collections.emptyList(), expectedId, subject);
  }

  public static void registerAndVerifySchema(
      RestService restService,
      String schemaString,
      List<SchemaReference> references,
      int expectedId,
      String subject)
      throws IOException, RestClientException {
    int registeredId =
        restService.registerSchema(schemaString, YangSchema.TYPE, references, subject).getId();
    assertEquals(expectedId, registeredId, "Registering a new schema should succeed");
    assertEquals(
        schemaString.trim(),
        restService.getId(expectedId).getSchemaString().trim(),
        "Registered schema should be found");
  }

  @Test
  public void testBasic() throws Exception {
    String subject1 = "testTopic1";
    String subject2 = "testTopic2";
    int schemasInSubject1 = 10;
    List<Integer> allVersionsInSubject1 = new ArrayList<>();
    List<String> allSchemasInSubject1 = getRandomYangSchemas(schemasInSubject1);
    int schemasInSubject2 = 5;
    List<Integer> allVersionsInSubject2 = new ArrayList<>();
    List<String> allSchemasInSubject2 = getRandomYangSchemas(schemasInSubject2);
    List<String> allSubjects = new ArrayList<>();

    // test getAllSubjects with no existing data
    assertEquals(
        allSubjects,
        restApp.restClient.getAllSubjects(),
        "Getting all subjects should return empty");

    // test registering and verifying new schemas in subject1
    int schemaIdCounter = 1;
    for (int i = 0; i < schemasInSubject1; i++) {
      String schema = allSchemasInSubject1.get(i);
      int expectedVersion = i + 1;
      registerAndVerifySchema(restApp.restClient, schema, schemaIdCounter, subject1);
      schemaIdCounter++;
      allVersionsInSubject1.add(expectedVersion);
    }
    allSubjects.add(subject1);

    // test re-registering existing schemas
    for (int i = 0; i < schemasInSubject1; i++) {
      int expectedId = i + 1;
      String schemaString = allSchemasInSubject1.get(i);
      RegisterSchemaResponse foundId =
          restApp.restClient.registerSchema(
              schemaString, YangSchema.TYPE, Collections.emptyList(), subject1);
      assertEquals(
          expectedId,
          foundId.getId(),
          "Re-registering an existing schema should return the existing version");
    }

    // test registering schemas in subject2
    for (int i = 0; i < schemasInSubject2; i++) {
      String schema = allSchemasInSubject2.get(i);
      int expectedVersion = i + 1;
      registerAndVerifySchema(restApp.restClient, schema, schemaIdCounter, subject2);
      schemaIdCounter++;
      allVersionsInSubject2.add(expectedVersion);
    }
    allSubjects.add(subject2);

    // test getAllVersions with existing data
    assertEquals(
        allVersionsInSubject1,
        restApp.restClient.getAllVersions(subject1),
        "Getting all versions from subject1 should match all registered versions");
    assertEquals(
        allVersionsInSubject2,
        restApp.restClient.getAllVersions(subject2),
        "Getting all versions from subject2 should match all registered versions");

    // test getAllSubjects with existing data
    assertEquals(
        allSubjects,
        restApp.restClient.getAllSubjects(),
        "Getting all subjects should match all registered subjects");
  }

  @Test
  public void testSchemaReferences() throws Exception {
    Map<String, String> schemas = getYangSchemaWithDependencies();
    String subject = "ref";
    registerAndVerifySchema(restApp.restClient, schemas.get("ref"), 1, subject);

    RegisterSchemaRequest request = new RegisterSchemaRequest();
    request.setSchema(schemas.get("root"));
    request.setSchemaType(YangSchema.TYPE);
    SchemaReference ref = new SchemaReference("ref", "ref", 1);
    List<SchemaReference> refs = Collections.singletonList(ref);
    request.setReferences(refs);
    int registeredId = restApp.restClient.registerSchema(request, "root", false).getId();
    assertEquals(2, registeredId, "Registering a new schema should succeed");

    SchemaString schemaString = restApp.restClient.getId(2);
    // the newly registered schema should be immediately readable on the leader
    assertEquals(
        schemas.get("root"), schemaString.getSchemaString(), "Registered schema should be found");

    assertEquals(refs, schemaString.getReferences(), "Schema dependencies should be found");

    YangSchemaContext context = YangStatementRegister.getInstance().getSchemeContextInstance();
    YangSchemaUtils.parseYangString("root", RootYangSchema, context);
    Module module = context.getModules().get(0);

    YangSchema schema =
        new YangSchema(RootYangSchema, context, module, refs, Collections.emptyMap());
    Schema registeredSchema =
        restApp.restClient.lookUpSubjectVersion(
            schema.canonicalString(), YangSchema.TYPE, schema.references(), "root", false);
    assertEquals(2, registeredSchema.getId(), "Registered schema should be found");
  }

  @Test
  public void testIETFYang10Schema() throws Exception {
    Map<String, String> schemas = getIETFYang10SchemaWithDependencies();
    String yangTypes = "ietf-yang-types";
    registerAndVerifySchema(restApp.restClient, schemas.get(yangTypes), 1, yangTypes);

    String interfaces = "ietf-interfaces";
    RegisterSchemaRequest request = new RegisterSchemaRequest();
    request.setSchema(schemas.get(interfaces));
    request.setSchemaType(YangSchema.TYPE);
    SchemaReference ref = new SchemaReference(yangTypes, yangTypes, 1);
    List<SchemaReference> refs = Collections.singletonList(ref);
    request.setReferences(refs);
    int registeredId = restApp.restClient.registerSchema(request, interfaces, false).getId();
    assertEquals(2, registeredId, "Registering a new schema should succeed");

    SchemaString schemaString = restApp.restClient.getId(2);
    // the newly registered schema should be immediately readable on the leader
    assertEquals(
        schemas.get(interfaces),
        schemaString.getSchemaString(),
        "Registered schema should be found");

    assertEquals(refs, schemaString.getReferences(), "Schema dependencies should be found");

    YangSchemaContext context = YangStatementRegister.getInstance().getSchemeContextInstance();
    YangSchemaUtils.parseYangString(interfaces, schemas.get(interfaces), context);
    Module module = context.getModules().get(0);

    YangSchema schema =
        new YangSchema(schemas.get(interfaces), context, module, refs, Collections.emptyMap());
    Schema registeredSchema =
        restApp.restClient.lookUpSubjectVersion(
            schema.canonicalString(), YangSchema.TYPE, schema.references(), interfaces, false);
    assertEquals(2, registeredSchema.getId(), "Registered schema should be found");
  }

  @Test
  public void testIETFYang11Schema() throws Exception {
    Map<String, String> schemas = getIETFYang11SchemaWithDependencies();
    String yangTypes = "ietf-yang-types";
    registerAndVerifySchema(restApp.restClient, schemas.get(yangTypes), 1, yangTypes);

    String interfaces = "ietf-interfaces";
    RegisterSchemaRequest request = new RegisterSchemaRequest();
    request.setSchema(schemas.get(interfaces));
    request.setSchemaType(YangSchema.TYPE);
    SchemaReference ref = new SchemaReference(yangTypes, yangTypes, 1);
    List<SchemaReference> refs = Collections.singletonList(ref);
    request.setReferences(refs);
    int registeredId = restApp.restClient.registerSchema(request, interfaces, false).getId();
    assertEquals(2, registeredId, "Registering a new schema should succeed");

    SchemaString schemaString = restApp.restClient.getId(2);
    // the newly registered schema should be immediately readable on the leader
    assertEquals(
        schemas.get(interfaces),
        schemaString.getSchemaString(),
        "Registered schema should be found");

    assertEquals(refs, schemaString.getReferences(), "Schema dependencies should be found");

    YangSchemaContext context = YangStatementRegister.getInstance().getSchemeContextInstance();
    YangSchemaUtils.parseYangString(interfaces, schemas.get(interfaces), context);
    Module module = context.getModules().get(0);

    YangSchema schema =
        new YangSchema(schemas.get(interfaces), context, module, refs, Collections.emptyMap());
    Schema registeredSchema =
        restApp.restClient.lookUpSubjectVersion(
            schema.canonicalString(), YangSchema.TYPE, schema.references(), interfaces, false);
    assertEquals(2, registeredSchema.getId(), "Registered schema should be found");
  }

  @Test
  public void testIETFYangSchemaCompatibility() throws Exception {
    // Registering dependencies
    Map<String, String> schemasYang10 = getIETFYang10SchemaWithDependencies();
    Map<String, String> schemasYang11 = getIETFYang11SchemaWithDependencies();
    String yangTypesSubject = "ietf-yang-types";
    registerAndVerifySchema(
        restApp.restClient, schemasYang10.get(yangTypesSubject), 1, yangTypesSubject);
    registerAndVerifySchema(
        restApp.restClient, schemasYang11.get(yangTypesSubject), 2, yangTypesSubject);

    // Register interfaces for Yang 1.0 version
    String interfacesSubject = "ietf-interfaces";
    RegisterSchemaRequest requestYang10 = new RegisterSchemaRequest();
    requestYang10.setSchema(schemasYang10.get(interfacesSubject));
    requestYang10.setSchemaType(YangSchema.TYPE);
    SchemaReference refYang10 = new SchemaReference(yangTypesSubject, yangTypesSubject, 1);
    List<SchemaReference> refsYang10 = Collections.singletonList(refYang10);
    requestYang10.setReferences(refsYang10);
    int registeredId =
        restApp.restClient.registerSchema(requestYang10, interfacesSubject, false).getId();
    assertEquals(3, registeredId, "Registering a new schema should succeed");

    restApp.restClient.updateCompatibility(CompatibilityLevel.BACKWARD.name, interfacesSubject);
    // Register backward-incompatible interfaces for Yang 1.1
    RegisterSchemaRequest requestYang11 = new RegisterSchemaRequest();
    requestYang11.setSchema(schemasYang11.get(interfacesSubject));
    requestYang11.setSchemaType(YangSchema.TYPE);
    SchemaReference refYang11 = new SchemaReference(yangTypesSubject, yangTypesSubject, 2);
    List<SchemaReference> refsYang11 = Collections.singletonList(refYang11);
    requestYang11.setReferences(refsYang11);

    boolean isCompatible =
        restApp
            .restClient
            .testCompatibility(requestYang11, interfacesSubject, "latest", false, true)
            .isEmpty();
    assertFalse(isCompatible, "Schema should be incompatible with specified version");
  }

  @Test
  public void testSchemaMissingReferences() throws Exception {
    Map<String, String> schemas = getYangSchemaWithDependencies();

    RegisterSchemaRequest request = new RegisterSchemaRequest();
    request.setSchema(schemas.get("root"));
    request.setSchemaType(YangSchema.TYPE);
    request.setReferences(Collections.emptyList());
    assertThrows(
        RestClientException.class, () -> restApp.restClient.registerSchema(request, "root", false));
  }

  @Test
  public void testBad() throws Exception {
    String subject1 = "testTopic1";
    List<String> allSubjects = new ArrayList<>();

    // test getAllSubjects with no existing data
    assertEquals(
        allSubjects,
        restApp.restClient.getAllSubjects(),
        "Getting all subjects should return empty");

    try {
      registerAndVerifySchema(restApp.restClient, getBadSchema(), 1, subject1);
      fail("Registering bad schema should fail with " + Errors.INVALID_SCHEMA_ERROR_CODE);
    } catch (RestClientException rce) {
      assertEquals(Errors.INVALID_SCHEMA_ERROR_CODE, rce.getErrorCode(), "Invalid schema");
    }

    try {
      registerAndVerifySchema(
          restApp.restClient,
          getRandomYangSchemas(1).get(0),
          Collections.singletonList(new SchemaReference("bad", "bad", 100)),
          1,
          subject1);
      fail("Registering bad reference should fail with " + Errors.INVALID_SCHEMA_ERROR_CODE);
    } catch (RestClientException rce) {
      assertEquals(Errors.INVALID_SCHEMA_ERROR_CODE, rce.getErrorCode(), "Invalid schema");
    }

    // test getAllSubjects with existing data
    assertEquals(
        allSubjects,
        restApp.restClient.getAllSubjects(),
        "Getting all subjects should match all registered subjects");
  }

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

  public static Map<String, String> getIETFYang10SchemaWithDependencies() {
    String typesSchema = readFile("yang/ietf-yang-types@2010-09-24.yang");
    String interfacesSchema = readFile("yang/ietf-interfaces@2014-05-08.yang");
    Map<String, String> schemas = new HashMap<>();
    schemas.put("ietf-yang-types", typesSchema);
    schemas.put("ietf-interfaces", interfacesSchema);
    return schemas;
  }

  public static Map<String, String> getIETFYang11SchemaWithDependencies() {
    String typesSchema = readFile("yang/ietf-yang-types@2013-07-15.yang");
    String interfacesSchema = readFile("yang/ietf-interfaces@2018-02-20.yang");
    Map<String, String> schemas = new HashMap<>();
    schemas.put("ietf-yang-types", typesSchema);
    schemas.put("ietf-interfaces", interfacesSchema);
    return schemas;
  }

  public static Map<String, String> getFanoYangSchemaWithDependencies() {
    String typesSchema = readFile("yang/ietf-yang-types@2013-07-15.yang");
    String interfacesSchema = readFile("yang/clean/ietf-interfaces@2018-02-20.yang");
    Map<String, String> schemas = new HashMap<>();
    schemas.put("ietf-yang-types", typesSchema);
    schemas.put("ietf-interfaces", interfacesSchema);
    return schemas;
  }

  public static String getBadSchema() {
    return "module bad {\n";
  }

  public static String readFile(String fileName) {
    ClassLoader classLoader = ClassLoader.getSystemClassLoader();
    InputStream is = classLoader.getResourceAsStream(fileName);
    if (is != null) {
      BufferedReader reader = new BufferedReader(new InputStreamReader(is));
      return reader.lines().collect(Collectors.joining(System.lineSeparator()));
    }
    return null;
  }

  @Test
  public void testDebugFano() throws Exception {
    // Registering dependencies
    Map<String, String> schemasFano = getFanoYangSchemaWithDependencies();
    Map<String, String> schemasYang11 = getIETFYang11SchemaWithDependencies();
    String yangTypesSubject = "ietf-yang-types";
    registerAndVerifySchema(
        restApp.restClient, schemasFano.get(yangTypesSubject), 1, yangTypesSubject);
    registerAndVerifySchema(
        restApp.restClient, schemasYang11.get(yangTypesSubject), 1, yangTypesSubject);

    // Register interfaces for FANO
    String interfacesSubject = "ietf-interfaces";
    RegisterSchemaRequest requestYangFANO = new RegisterSchemaRequest();
    requestYangFANO.setSchema(schemasFano.get(interfacesSubject));
    requestYangFANO.setSchemaType(YangSchema.TYPE);
    SchemaReference refYangFANO = new SchemaReference(yangTypesSubject, yangTypesSubject, 1);
    List<SchemaReference> refsYang10 = Collections.singletonList(refYangFANO);
    requestYangFANO.setReferences(refsYang10);
    int registeredId =
        restApp.restClient.registerSchema(requestYangFANO, interfacesSubject, false).getId();
    assertEquals(registeredId, 2, "Registering a new schema should succeed");

    restApp.restClient.updateCompatibility(CompatibilityLevel.BACKWARD.name, interfacesSubject);
    // Register backward-incompatible interfaces for Yang 1.1
    RegisterSchemaRequest requestYang11 = new RegisterSchemaRequest();
    requestYang11.setSchema(schemasYang11.get(interfacesSubject));
    requestYang11.setSchemaType(YangSchema.TYPE);
    SchemaReference refYang11 = new SchemaReference(yangTypesSubject, yangTypesSubject, 1);
    List<SchemaReference> refsYang11 = Collections.singletonList(refYang11);
    requestYang11.setReferences(refsYang11);

    List<String> ret =
        restApp.restClient.testCompatibility(
            requestYang11, interfacesSubject, "latest", false, true);

    for (String s : ret) {
      System.err.println("XXX RET: " + s);
    }
    boolean isCompatible = ret.isEmpty();
    assertTrue(isCompatible, "Schema should be compatible with specified version");
  }
}
