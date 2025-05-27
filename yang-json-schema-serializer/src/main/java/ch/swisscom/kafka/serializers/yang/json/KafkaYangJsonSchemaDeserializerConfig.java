/*
 * Copyright 2025 INSA Lyon.
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

package ch.swisscom.kafka.serializers.yang.json;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import java.util.Map;
import org.apache.kafka.common.config.ConfigDef;

public class KafkaYangJsonSchemaDeserializerConfig extends AbstractKafkaSchemaSerDeConfig {

  public static final String YANG_JSON_FAIL_UNKNOWN_PROPERTIES =
      "yang.json.fail.unknown.properties";
  public static final boolean YANG_JSON_FAIL_UNKNOWN_PROPERTIES_DEFAULT = true;
  public static final String YANG_JSON_FAIL_UNKNOWN_PROPERTIES_DOC =
      "Whether to fail " + "deserialization if unknown YANG-JSON properties are encountered";
  public static final String YANG_JSON_FAIL_INVALID_SCHEMA = "yang.json.fail.invalid.schema";
  public static final boolean YANG_JSON_FAIL_INVALID_SCHEMA_DEFAULT = false;
  public static final String YANG_JSON_FAIL_INVALID_SCHEMA_DOC =
      "Whether to fail deserialization" + "if the YANG-JSON payload does not match the schema";

  public static final String YANG_JSON_KEY_TYPE = "yang.json.key.type";
  public static final String YANG_JSON_KEY_TYPE_DEFAULT = Object.class.getName();
  public static final String YANG_JSON_KEY_TYPE_DOC =
      "Classname of the type that the message key " + "should be deserialized to";

  public static final String YANG_JSON_VALUE_TYPE = "yang.json.value.type";
  public static final String YANG_JSON_VALUE_TYPE_DEFAULT = Object.class.getName();
  public static final String YANG_JSON_VALUE_TYPE_DOC =
      "Classname of the type that the message " + "value should be deserialized to";

  private static ConfigDef config;

  static {
    config =
        baseConfigDef()
            .define(
                YANG_JSON_FAIL_UNKNOWN_PROPERTIES,
                ConfigDef.Type.BOOLEAN,
                YANG_JSON_FAIL_UNKNOWN_PROPERTIES_DEFAULT,
                ConfigDef.Importance.LOW,
                YANG_JSON_FAIL_UNKNOWN_PROPERTIES_DOC)
            .define(
                YANG_JSON_FAIL_INVALID_SCHEMA,
                ConfigDef.Type.BOOLEAN,
                YANG_JSON_FAIL_INVALID_SCHEMA_DEFAULT,
                ConfigDef.Importance.MEDIUM,
                YANG_JSON_FAIL_INVALID_SCHEMA_DOC)
            .define(
                YANG_JSON_KEY_TYPE,
                ConfigDef.Type.CLASS,
                YANG_JSON_KEY_TYPE_DEFAULT,
                ConfigDef.Importance.MEDIUM,
                YANG_JSON_KEY_TYPE_DOC)
            .define(
                YANG_JSON_VALUE_TYPE,
                ConfigDef.Type.CLASS,
                YANG_JSON_VALUE_TYPE_DEFAULT,
                ConfigDef.Importance.MEDIUM,
                YANG_JSON_VALUE_TYPE_DOC);
  }

  public KafkaYangJsonSchemaDeserializerConfig(Map<?, ?> props) {
    super(config, props);
  }
}
