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

import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import java.util.List;
import org.yangcentral.yangkit.base.YangElement;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.model.api.stmt.Module;
import org.yangcentral.yangkit.model.api.stmt.YangStatement;
import org.yangcentral.yangkit.parser.YangParser;
import org.yangcentral.yangkit.parser.YangParserEnv;
import org.yangcentral.yangkit.parser.YangParserException;

public class YangSchemaUtils {

  public static Module parseYangString(String name, String schemaString, YangSchemaContext context)
      throws YangParserException {

    YangParser YANG_PARSER = new YangParser();

    YangParserEnv yangParserEnv = new YangParserEnv();
    yangParserEnv.setYangStr(schemaString);
    yangParserEnv.setFilename(name);
    yangParserEnv.setCurPos(0);
    List<YangElement> elementList = YANG_PARSER.parseYang(schemaString, yangParserEnv);
    Module parsedModule = null;
    for (YangElement element : elementList) {
      if (element instanceof YangStatement) {
        if (parsedModule != null) {
          // we should have only one top-level YangStatement. Throw exception in case upstream (yangkit) logic change.
          throw new YangParserException(null, null, "multiple top-level YangStatements found: " + name);
        }
        parsedModule = (Module) element;
        context.addModule(parsedModule);
      }
    }
    String moduleName = parsedModule != null ? parsedModule.getModuleId().getModuleName() : name;
    context.getParseResult().put(moduleName, elementList);
    return parsedModule;
  }

  public static Module parseSchema(Schema schema, YangSchemaContext context)
      throws YangParserException {
    return parseYangString(schema.getSubject(), schema.getSchema(), context);
  }

  // TODO: disable it in production.
  public static long countStatements(YangStatement statement) {
    long count = 1;
    for (YangElement subElement : statement.getSubElements()) {
      if (subElement instanceof YangStatement) {
        count += countStatements((YangStatement) subElement);
      }
    }
    return count;
  }

  public static YangSchema copyOf(YangSchema schema) {
    return schema.copy();
  }
}
