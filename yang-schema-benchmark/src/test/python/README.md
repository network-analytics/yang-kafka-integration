# YANG Schema Registry Benchmark Tests

## Overview

This directory contains Python-based benchmark testing scripts designed to test the performance and behavior of 
a Schema Registry (SR) with YANG schema plugin integration. The tests simulate scenarios where multiple YANG schemas 
are registered to a SR.

The two main scripts are:

1. **`benchmark_yang_import.py`**: Registers a valid set of YANG modules (dependencies required by ietf-telemetry-message).
2. **`benchmark_yang_placeholder.py`**: Continuously registers unique YANG schema variants by replacing placeholders in a 
   template schema. This creates variations of the ietf-telemetry-message module with random content (module name and 
   internal content) to ensure the SR treats each as a new schema and triggers its internal processing for new schema 
   registration.


## Test Environment

The test can be run in an environment with the following prerequisites:

- Virtual Python (e.g. 3.12+) environment
- Access to a Schema Registry with YANG plugin enabled
- Network connectivity to the Schema Registry endpoint

### Installation

1. Create a Python virtual environment:
```bash
cd src/test/python
python3 -m venv venv
source venv/bin/activate
```

2. Install dependencies:
```bash
pip install -r requirements.txt
```

## Test Scripts

### 1. `benchmark_yang_import.py`

**Purpose**: Registers a valid set of YANG modules (which would be needed for YANG module of ietf-telemetry-message).

**What it does**:
- Reads YANG schemas from `../resources/yang-import/` directory
- Automatically extracts `import` statements to identify dependencies
- Registers schemas in sorted order (by filename) to ensure dependencies are satisfied
- Runs in an infinite loop, continuously re-registering all schemas
- Includes a sleep time between loop cycles

**Usage**:
```bash
python3 benchmark_yang_import.py <schema-registry-url>
```

**Expected Output**:
```
[INFO] Processing /path/to/00-ietf-yang-types.yang for subject: ietf-yang-types-value
[INFO] Successfully registered schema. Response: {'id': 1}
[INFO] Processing /path/to/03-ietf-inet-types.yang for subject: ietf-inet-types-value
[INFO] Successfully registered schema. Response: {'id': 2}
...
```


---

### 2. `benchmark_yang_placeholder.py`

**Purpose**: Stress test the SR by continuously registering unique YANG schema variants.

**What it does**:
- Reads template `ietf-telemetry-message-PLACEHOLDER.yang`
- Replaces "PLACEHOLDER" with random strings to create unique schema variants
- Registers each unique schema variant with its own subject name
- Continues indefinitely, simulating high-volume schema registration

**Prerequisites**:
Since this schema variant is based on the `ietf-telemetry-message` module which has dependencies, 
**first register those reference YANG modules** using:

```bash
python3 benchmark_yang_import.py <schema-registry-url>
```

(You can stop it manually after one complete cycle with Ctrl+C)

**Usage**:
```bash
python3 benchmark_yang_placeholder.py <schema-registry-url> [num_schemas_between_breaks]
```

**Parameters**:
- `schema-registry-url`: URL of the Schema Registry (required)
- `num_schemas_between_breaks`: Number of schemas to register before a 1-second sleep (default: 1)

**Examples**:
```bash
# Register 30 schemas, sleep, repeat
python3 benchmark_yang_placeholder.py https://schema-registry-url.com 30

# Register 100 schemas between breaks for high-load testing
python3 benchmark_yang_placeholder.py https://schema-registry-url.com 100
```

**Expected Output**:
```
[INFO] Found dependencies to reference: ietf-yang-types, ietf-inet-types, ietf-platform-manifest, ietf-yang-structure-ext
[INFO] Registering schema with random string: aBc123XyZ456789QrStU
[INFO] Successfully registered schema. Response: {'id': 842}
[INFO] Registering schema with random string: PqR789MnOp234567WxYz
[INFO] Successfully registered schema. Response: {'id': 843}
[INFO] 30 schema registered, take a break.
...
```

---

## Monitoring and Performance

### Performance Metrics to Monitor

When running these tests, it would be great to monitor 
(not included in this repo but can be done using tools like `top`, `htop`, or Schema Registry's own metrics endpoint):

1. **Schema Registry Metrics**:
   - CPU usage
   - Memory consumption
   - Response times
   - Number of registered schemas
   - Compatibility check duration

2. **Test Script Observations**:
   - Registration success rate
   - Time between registrations
   - Error patterns

3. **Potential Bottlenecks**:
   - Synchronized parsing operations
   - Schema validation overhead
   - Compatibility checking against all versions
   - Memory leaks (watch for continuously growing memory)

### Common Error Responses

- **`422 Unprocessable Entity`**: Schema validation failed or compatibility issue
- **`409 Conflict`**: Schema already exists (version conflict)
- **`404 Not Found`**: Schema Registry endpoint not found
- **Connection errors**: Schema Registry unavailable or network issues
- **Certificate errors**: SSL verification issues (scripts disable SSL verification by default)

---

## Running Schema Registry Locally with Docker Compose

For local testing, you can run Schema Registry with the YANG plugin using Docker Compose:

```yaml
---
services:
  schema-registry:
    image: confluentinc/cp-schema-registry:latest
    hostname: schema-registry
    container_name: kafka-schema-registry
    ports:
      - "8081:8081"
    volumes:
      - ./yang-schema-registry-plugin.jar:/usr/share/java/schema-registry/yang-schema-registry-plugin.jar
    environment:
      SCHEMA_REGISTRY_HOST_NAME: schema-registry
      SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS: "bootstrap-url:9096"
      SCHEMA_REGISTRY_LISTENERS: http://0.0.0.0:8081
      
      # Custom configs
      SCHEMA_REGISTRY_KAFKASTORE_TOPIC: kafka-schema-topic-name
      
      # Configure according to your environment if needed
      # SCHEMA_REGISTRY_KAFKASTORE_SECURITY_PROTOCOL: 
      # SCHEMA_REGISTRY_KAFKASTORE_SASL_MECHANISM: 
      # SCHEMA_REGISTRY_KAFKASTORE_SASL_JAAS_CONFIG: 
      
      # Enable YANG schema provider
      SCHEMA_REGISTRY_SCHEMA_PROVIDERS: "ch.swisscom.kafka.schemaregistry.yang.YangSchemaProvider"
      
      # Optional: Increase heap size for benchmark tests
      SCHEMA_REGISTRY_HEAP_OPTS: "-Xms12g -Xmx12g -XX:+ExitOnOutOfMemoryError"
    
    restart: unless-stopped
```

