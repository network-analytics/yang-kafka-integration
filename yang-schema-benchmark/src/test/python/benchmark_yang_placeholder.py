import sys
import time
import json
import random
import re
import string
import requests
import logging
from pathlib import Path

logging.basicConfig(level=logging.INFO, format='[%(levelname)s] %(message)s')

SCHEMA_REGISTRY_URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8081"
NUM_SCHEMAS_BETWEEN_BREAKS = int(sys.argv[2]) if len(sys.argv) > 2 else 1
SCRIPT_DIR = Path(__file__).resolve().parent
RESOURCES_DIR = SCRIPT_DIR.parent / "resources"
PLACEHOLDER_SCHEMA_PATH = RESOURCES_DIR / "yang-placeholder/ietf-telemetry-message-PLACEHOLDER.yang"
SUBJECT_NAME = "ietf-telemetry-message-PLACEHOLDER-value"
IMPORT_PATTERN = re.compile(r"^\s*import\s+([A-Za-z0-9_.-]+)\s*(?:\{|;)")

requests.packages.urllib3.disable_warnings()


def extract_imports(schema_path: Path) -> list[str]:
    imports = []
    with open(schema_path, "r", encoding="utf-8") as f:
        for raw_line in f:
            line = raw_line.split("//", 1)[0].strip()
            if not line:
                continue
            match = IMPORT_PATTERN.match(line)  # a quick / dirty way of finding out import
            if match:
                imports.append(match.group(1))
    return imports


def run_benchmark_loop():
    if not PLACEHOLDER_SCHEMA_PATH.is_file():
        logging.error(f"Schema file not found: {PLACEHOLDER_SCHEMA_PATH}")
        sys.exit(1)

    try:
        with open(PLACEHOLDER_SCHEMA_PATH, "r", encoding="utf-8") as f:
            original_schema_content = f.read()
    except IOError as e:
        logging.error(f"Could not read file {PLACEHOLDER_SCHEMA_PATH}: {e}")
        sys.exit(1)

    dependencies = extract_imports(PLACEHOLDER_SCHEMA_PATH)
    if dependencies:
        logging.info(f"Found dependencies to reference: {', '.join(dependencies)}")
    else:
        logging.info("No dependencies found to reference.")

    count = 0
    while True:
        random_str = "".join(random.choices(string.ascii_letters + string.digits, k=20))
        schema_content = original_schema_content.replace("PLACEHOLDER", random_str)

        url = f"{SCHEMA_REGISTRY_URL}/subjects/{SUBJECT_NAME}/versions".replace("PLACEHOLDER", random_str)

        payload = {"schemaType": "YANG", "schema": schema_content}
        if dependencies:
            references = [{"name": dep, "subject": f"{dep}-value", "version": 1} for dep in dependencies]
            payload["references"] = references

        logging.info(f"Registering schema with random string: {random_str}")

        try:
            response = requests.post(
                url,
                headers={"Content-Type": "application/vnd.schemaregistry.v1+json"},
                data=json.dumps(payload),
                verify=False,
            )
            response.raise_for_status()
            logging.info(f"Successfully registered schema. Response: {response.json()}")
        except requests.exceptions.RequestException as e:
            logging.error(f"Failed to register schema: {e}")

        count += 1

        if count % NUM_SCHEMAS_BETWEEN_BREAKS == 0:
            logging.info(f"{NUM_SCHEMAS_BETWEEN_BREAKS} schema registered, take a break.")
            time.sleep(1)


def main():
    if len(sys.argv) > 3:
        logging.error("Usage: python3 benchmark_yang_placeholder.py [schema-registry-url] [num_schemas_between_breaks]")
        sys.exit(1)

    run_benchmark_loop()


if __name__ == "__main__":
    main()

