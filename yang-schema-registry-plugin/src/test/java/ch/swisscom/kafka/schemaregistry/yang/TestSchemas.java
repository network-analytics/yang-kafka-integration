/*
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

public class TestSchemas {
  static final String SIMPLE_SCHEMA =
      "module a-module {\n"
          + "  yang-version 1.1;\n"
          + "  namespace \"urn:example:a-module\";\n"
          + "  prefix a;\n"
          + "\n"
          + "  organization\n"
          + "    \"Test Organization\";\n"
          + "  contact\n"
          + "    \"Ahmed Elhassany <mailto:ahmed.elhassany@swisscom.com>\";\n"
          + "  description\n"
          + "    \"Simple Test module with one typedef and a container of one leaf\";\n"
          + "  reference\n"
          + "    \"testing\";\n"
          + "\n"
          + "  revision 2023-02-01 {\n"
          + "    reference\n"
          + "      \"first revision\";\n"
          + "  }\n"
          + "\n"
          + "  typedef aType {\n"
          + "    type int8;\n"
          + "    description\n"
          + "      \"Simple int8 type for testing\";\n"
          + "  }\n"
          + "\n"
          + "  container x {\n"
          + "    description\n"
          + "      \"Test container\";\n"
          + "    leaf aLeaf {\n"
          + "      type aType;\n"
          + "      description\n"
          + "        \"Example leaf\";\n"
          + "    }\n"
          + "  }\n"
          + "}\n";

  static final String SIMPLE_SCHEMA_REORDERED =
      "module a-module {\n"
          + "  yang-version 1.1;\n"
          + "  namespace \"urn:example:a-module\";\n"
          + "  prefix a;\n"
          + "\n"
          + "  organization\n"
          + "    \"Test Organization\";\n"
          + "  contact\n"
          + "    \"Ahmed Elhassany <mailto:ahmed.elhassany@swisscom.com>\";\n"
          + "  description\n"
          + "    \"Simple Test module with one typedef and a container of one leaf\";\n"
          + "  reference\n"
          + "    \"testing\";\n"
          + "\n"
          + "  revision 2023-02-01 {\n"
          + "    reference\n"
          + "      \"first revision\";\n"
          + "  }\n"
          + "\n"
          + "  container x {\n"
          + "    description\n"
          + "      \"Test container\";\n"
          + "    leaf aLeaf {\n"
          + "      type aType;\n"
          + "      description\n"
          + "        \"Example leaf\";\n"
          + "    }\n"
          + "  }\n"
          + "  typedef aType {\n"
          + "    type int8;\n"
          + "    description\n"
          + "      \"Simple int8 type for testing\";\n"
          + "  }\n"
          + "}\n";
}
