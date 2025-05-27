# YANG Kafka native extensions

This repo provides components needed for native YANG in apache Kafka. Currently, includes the following components:

1. yang-schema-registry-plugin: A plugin for Confluent's schema register
2. yang-json-schema-serializer: Serializers and Deserializers for YANG-JSON
3. yang-cbor-schema-serializer: Serializers and Deserializers for YANG-CBOR 

## Dependencies
- YangKit [[Repository (branch feature/yangkit-complete-validation)]](https://github.com/network-analytics/yangkit/tree/feature/yangkit-complete-validation)

## Compiling from source
1. Compile YangKit fork from branch [[feature/yangkit-complete-validation]](https://github.com/network-analytics/yangkit/tree/feature/yangkit-complete-validation)
2. `maven clean install`

## Configuring yang-schema-registry-plugin

1. Download and configure Confluent platform: see https://docs.confluent.io/platform/7.5/installation/installing_cp. For
   the purpose of this documentation, we denote the installation dir of Confluent's platform as `${CONFLUENT_DIR}`
2. build the current project `mvn package`
3. Copy the yang-schema-registry-plugin-0.3-shaded.jar to Confluent's schema registry java libs directory
    ```shell
    cp yang-schema-registry-plugin/target/yang-schema-registry-plugin-0.3-shaded.jar ${CONFLUENT_DIR}/share/java/schema-registry/
    ``` 
4. Enable the yang schema plugin by added the line
   `schema.providers=com.swisscom.kafka.schemaregistry.yang.YangSchemaProvider` to
   `${CONFLUENT_DIR}/etc/schema-registry/schema-registry.properties`
   ```shell
   echo 'schema.providers=ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider' >> ${CONFLUENT_DIR}/etc/schema-registry/schema-registry.properties
   ```
5. Restart the schema registry. When the plugin is loaded successfully, a log containing the message
   `[ INFO] - io.confluent.kafka.schemaregistry.storage.KafkaSchemaRegistry -KafkaSchemaRegistry.java(272) -Registering schema provider for YANG: ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider`
   is printed

## Usage of Serializers without compiling from source
Version v0.0.3 contains uber-jars to be installed locally. See [Here](https://github.com/network-analytics/yang-kafka-integration/releases/tag/v0.0.3).

1. Download release v0.0.3
2. Unzip `uber-jars-v0.0.3.zip`
3. Install jar locally
   - JSON
     ```shell
     mvn install:install-file -Dfile=<download_path>/kafka-yang-json-schema-serializer-0.3-shaded.jar -DgroupId=ch.swisscom -DartifactId=kafka-yang-json-schema-serializer -Dversion=0.3 -Dpackaging=jar
     ```
   - CBOR
     ```shell
     mvn install:install-file -Dfile=<download_path>/kafka-yang-cbor-schema-serializer-0.3-shaded.jar -DgroupId=ch.swisscom -DartifactId=kafka-yang-cbor-schema-serializer -Dversion=0.3 -Dpackaging=jar
     ```
4. Use as clients. Examples can be found in this repository (branch `new-namespaces`): [schema-registry-samples](https://github.com/network-analytics/schema-registry-samples/tree/new-namespaces)
