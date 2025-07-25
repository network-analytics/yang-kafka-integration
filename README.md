# YANG Kafka native extensions

This repo provides components needed for native YANG in apache Kafka. Currently, includes the following components:

1. yang-schema-registry-plugin: A plugin to enable YANG support in Confluent's schema registry
2. yang-json-schema-serializer: Serializers and Deserializers used by producers/consumers for YANG with JSON encoding
3. yang-json-schema-serializer: Serializers and Deserializers used by producers/consumers for YANG with CBOR encoding

## Download

The `yang-schema-registry-plugin`, `yang-json-schema-serializer`, and `yang-json-schema-serializer` components can be
either built from source or installed directly from the pre-built JARS.

### Pre-built jars

- For pre-built jars download the required jars
  from [GitHub Releases](https://github.com/network-analytics/yang-kafka-integration/releases/tag/v0.0.3).
- To install the schema registry plugin
  see [Configuring yang-schema-registry-plugin](#Configuring-yang-schema-registry-plugin)
- To use JAVA producers/consumers in your Java project see  [Using YANG serdes](#Using YANG serdes)

### Compile from source

1. Compile and install YangKit fork from
   branch [[feature/yangkit-complete-validation]](https://github.com/network-analytics/yangkit/tree/feature/yangkit-complete-validation)
   ```bash
   git clone https://github.com/network-analytics/yangkit.git
   cd yangkit
   git checkout feature/yangkit-complete-validation
   mvn package install
   ```
2. Compile and install the YANG kafka native integration
   ```bash
   git clone https://github.com/network-analytics/yang-kafka-integration.git
   cd yang-kafka-integration
   mvn package install
   ```
3. Check the produced jars
    1. yang-schema-registry-plugin: `yang-schema-registry-plugin/target/yang-schema-registry-plugin-0.0.3-shaded.jar`
    2. yang-json-schema-serializer:
       `yang-json-schema-serializer/target/kafka-yang-json-schema-serializer-0.0.3-shaded.jar`
    3. yang-cbor-schema-serializer:
       `yang-cbor-schema-serializer/target/kafka-yang-cbor-schema-serializer-0.0.3-shaded.jar`
4. To install the schema registry plugin
   see [Configuring yang-schema-registry-plugin](#Configuring-yang-schema-registry-plugin)
5. To use JAVA producers/consumers in your Java project see  [Using YANG serdes](#Using YANG serdes)

## Configuring yang-schema-registry-plugin

1. Download and configure Confluent platform: see https://docs.confluent.io/platform/7.5/installation/installing_cp. For
   the purpose of this documentation, we denote the installation dir of Confluent's platform as `${CONFLUENT_DIR}`
2. build the current project `mvn package`
3. Copy the yang-schema-registry-plugin-0.0.3-shaded.jar to Confluent's schema registry java libs directory
    ```shell
    cp yang-schema-registry-plugin/target/yang-schema-registry-plugin-0.0.3-shaded.jar ${CONFLUENT_DIR}/share/java/schema-registry/
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

## Using YANG serdes

To use the serdes in your JAVA project (Examples can be found in this
repository https://github.com/network-analytics/schema-registry-samples/tree/main), install the jars into your local
maven cache.

```bash
mvn install:install-file -Dfile=<download_path>/kafka-yang-json-schema-serializer-0.0.3-shaded.jar -DgroupId=ch.swisscom -DartifactId=kafka-yang-json-schema-serializer -Dversion=0.0.3 -Dpackaging=jar
mvn install:install-file -Dfile=<download_path>/kafka-yang-cbor-schema-serializer-0.0.3-shaded.jar -DgroupId=ch.swisscom -DartifactId=kafka-yang-cbor-schema-serializer -Dversion=0.0.3 -Dpackaging=jar
```

- In your JAVA project, import the jars:

```xml
    <!--- JSON Encoding -->
<dependency>
    <groupId>ch.swisscom</groupId>
    <artifactId>kafka-yang-json-schema-serializer</artifactId>
    <version>0.0.3</version>
    <scope>compile</scope>
</dependency>

        <!--- CBOR Encoding -->
<dependency>
<groupId>ch.swisscom</groupId>
<artifactId>kafka-yang-cbor-schema-serializer</artifactId>
<version>0.0.3</version>
<scope>compile</scope>
</dependency>
```
