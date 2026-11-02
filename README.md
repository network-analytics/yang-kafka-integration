# YANG Kafka native extensions

This repo provides components needed for native YANG in apache Kafka. Currently, includes the following components:

1. yang-schema-registry-plugin: A plugin to enable YANG support in Confluent's schema registry
2. yang-json-schema-serializer: Serializers and Deserializers used by producers/consumers for YANG with JSON encoding
3. yang-cbor-schema-serializer: Serializers and Deserializers used by producers/consumers for YANG with CBOR encoding

## Download

The `yang-schema-registry-plugin`, `yang-json-schema-serializer`, and `yang-cbor-schema-serializer` components can be
either built from source or installed directly from the pre-built JARS.

### Pre-built jars

- For pre-built jars download the required jars
  from [GitHub Releases](https://github.com/network-analytics/yang-kafka-integration/releases/tag/v0.0.6).
- To install the schema registry plugin
  see [Configuring yang-schema-registry-plugin](#configuring-yang-schema-registry-plugin)
- To use JAVA producers/consumers in your Java project see  [Using YANG serdes](#using-yang-serdes)

### Compile from source

Requires JDK 17 and Maven. `yangkit-common` 1.1.6-SNAPSHOT and `yangkit` 1.7.0 are not on Maven Central yet, so they
must be built from source first (same steps as [build.yml](.github/workflows/build.yml)).

1. Compile and install the YANG dependencies
   ```bash
   git clone https://github.com/yang-central/yangkit-common.git
   (cd yangkit-common && git checkout 0846d1f && mvn install -DskipTests)

   git clone https://github.com/yang-central/yangkit.git
   (cd yangkit && git checkout v1.7.0 && mvn install -DskipTests)

   git clone -b update-deps https://github.com/network-analytics/yang-compiler.git
   (cd yang-compiler && mvn install -DskipTests)

   git clone -b update-deps https://github.com/network-analytics/yang-comparator.git
   (cd yang-comparator && mvn install -DskipTests)
   ```
2. Compile and install the YANG kafka native integration
   ```bash
   git clone https://github.com/network-analytics/yang-kafka-integration.git
   cd yang-kafka-integration
   mvn package install
   ```
3. Check the produced jars
    1. yang-schema-registry-plugin: `yang-schema-registry-plugin/target/yang-schema-registry-plugin-0.0.6-shaded.jar`
    2. yang-json-schema-serializer:
       `yang-json-schema-serializer/target/kafka-yang-json-schema-serializer-0.0.6-shaded.jar`
    3. yang-cbor-schema-serializer:
       `yang-cbor-schema-serializer/target/kafka-yang-cbor-schema-serializer-0.0.6-shaded.jar`
4. To install the schema registry plugin
   see [Configuring yang-schema-registry-plugin](#configuring-yang-schema-registry-plugin)
5. To use JAVA producers/consumers in your Java project see  [Using YANG serdes](#using-yang-serdes)

## Configuring yang-schema-registry-plugin

1. Download and configure Confluent platform: see https://docs.confluent.io/platform/8.0/installation/installing_cp. For
   the purpose of this documentation, we denote the installation dir of Confluent's platform as `${CONFLUENT_DIR}`
2. build the current project `mvn package`
3. Copy the yang-schema-registry-plugin-0.0.6-shaded.jar to Confluent's schema registry java libs directory
    ```shell
    cp yang-schema-registry-plugin/target/yang-schema-registry-plugin-0.0.6-shaded.jar ${CONFLUENT_DIR}/share/java/schema-registry/
    ```
4. Enable the yang schema plugin by added the line
   `schema.providers=ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider` to
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
mvn install:install-file -Dfile=<download_path>/kafka-yang-json-schema-serializer-0.0.6-shaded.jar -DgroupId=ch.swisscom -DartifactId=kafka-yang-json-schema-serializer -Dversion=0.0.6 -Dpackaging=jar
mvn install:install-file -Dfile=<download_path>/kafka-yang-cbor-schema-serializer-0.0.6-shaded.jar -DgroupId=ch.swisscom -DartifactId=kafka-yang-cbor-schema-serializer -Dversion=0.0.6 -Dpackaging=jar
```

- In your JAVA project, import the jars:

```xml
    <!--- JSON Encoding -->
<dependency>
    <groupId>ch.swisscom</groupId>
    <artifactId>kafka-yang-json-schema-serializer</artifactId>
    <version>0.0.6</version>
    <scope>compile</scope>
</dependency>

        <!--- CBOR Encoding -->
<dependency>
<groupId>ch.swisscom</groupId>
<artifactId>kafka-yang-cbor-schema-serializer</artifactId>
<version>0.0.6</version>
<scope>compile</scope>
</dependency>
```
