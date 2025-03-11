# YANG Kafka native extensions

This repo provides components needed for native YANG in apache Kafka. Currently, includes the following components:

1. yang-schema-registry-plugin: A plugin for Confluent's schema register
2. TODO json and cbor serdes


## Configuring yang-schema-registry-plugin

1. Download and configure Confluent platform: see https://docs.confluent.io/platform/7.5/installation/installing_cp. For
   the purpose of this documentation, we denote the installation dir of Confluent's platform as `${CONFLUENT_DIR}`
2. build the current project `mvn package`
3. Copy the yang-schema-registry-plugin-0.1-shaded.jar to Confluent's schema registry java libs directory
    ```shell
    cp yang-schema-registry-plugin/target/yang-schema-registry-plugin-0.1-shaded.jar ${CONFLUENT_DIR}/share/java/schema-registry/
    ``` 
4. Enable the yang schema plugin by added the line
   `schema.providers=com.swisscom.kafka.schemaregistry.yang.YangSchemaProvider` to
   `${CONFLUENT_DIR}/etc/schema-registry/schema-registry.properties`
   ```shell
   echo 'schema.providers=com.swisscom.kafka.schemaregistry.yang.YangSchemaProvider' >> ${CONFLUENT_DIR}/etc/schema-registry/schema-registry.properties
   ```
5. Restart the schema registry. When the plugin is loaded successfully, a log containing the message
   `[ INFO] - io.confluent.kafka.schemaregistry.storage.KafkaSchemaRegistry -KafkaSchemaRegistry.java(272) -Registering schema provider for YANG: ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider`
   is printed