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

package fr.insa.kafka.serializers.yang.cbor;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import java.util.Map;
import org.apache.kafka.common.config.ConfigDef;

public class KafkaYangCborSchemaSerializerConfig extends AbstractKafkaSchemaSerDeConfig {

  public static final String YANG_CBOR_FAIL_UNKNOWN_PROPERTIES =
      "yang.cbor.fail.unknown.properties";
  public static final boolean YANG_CBOR_FAIL_UNKNOWN_PROPERTIES_DEFAULT = true;
  public static final String YANG_CBOR_FAIL_UNKNOWN_PROPERTIES_DOC =
      "Whether to fail serialization" + " if unknown YANG-CBOR properties are encountered";
  public static final String YANG_CBOR_FAIL_INVALID_SCHEMA = "yang.cbor.fail.invalid.schema";
  public static final boolean YANG_CBOR_FAIL_INVALID_SCHEMA_DEFAULT = false;
  public static final String YANG_CBOR_FAIL_INVALID_SCHEMA_DOC =
      "Whether to fail serialization if" + " the YANG-CBOR payload does not match the schema";

  private static ConfigDef config;

  static {
    config =
        baseConfigDef()
            .define(
                YANG_CBOR_FAIL_UNKNOWN_PROPERTIES,
                ConfigDef.Type.BOOLEAN,
                YANG_CBOR_FAIL_UNKNOWN_PROPERTIES_DEFAULT,
                ConfigDef.Importance.LOW,
                YANG_CBOR_FAIL_UNKNOWN_PROPERTIES_DOC)
            .define(
                YANG_CBOR_FAIL_INVALID_SCHEMA,
                ConfigDef.Type.BOOLEAN,
                YANG_CBOR_FAIL_INVALID_SCHEMA_DEFAULT,
                ConfigDef.Importance.MEDIUM,
                YANG_CBOR_FAIL_INVALID_SCHEMA_DOC);
  }

  public KafkaYangCborSchemaSerializerConfig(Map<?, ?> props) {
    super(config, props);
  }
}
