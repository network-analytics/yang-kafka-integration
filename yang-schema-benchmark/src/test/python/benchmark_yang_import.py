import os
import sys
import time
import json
import re
import requests
import logging
from pathlib import Path

logging.basicConfig(level=logging.INFO, format='[%(levelname)s] %(message)s')

"""
usage: 
python3 benchmark_yang_import.py https://schema-registry-url
"""
SCHEMA_REGISTRY_URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8081"
SCRIPT_DIR = Path(__file__).resolve().parent
RESOURCES_DIR = SCRIPT_DIR.parent / "resources"
YANG_IMPORTED_DIR = RESOURCES_DIR / "yang-import"
IMPORT_PATTERN = re.compile(r"^\s*import\s+([A-Za-z0-9_.-]+)\s*(?:\{|;)")

requests.packages.urllib3.disable_warnings()


def extract_imports(schema_path: Path) -> list[str]:
    imports = []
    with open(schema_path, "r", encoding="utf-8") as f:
        for raw_line in f:
            line = raw_line.split("//", 1)[0].strip()
            if not line:
                continue
            match = IMPORT_PATTERN.match(line)  # quick / dirty way of finding out import
            if match:
                imports.append(match.group(1))
    return imports


def process_file(filename: str) -> bool:
    schema_path = YANG_IMPORTED_DIR / f"{filename}.yang"
    if not schema_path.is_file():
        logging.error(f"Schema file not found: {schema_path}")
        return False

    subject_name = Path(filename).stem.split('-', 1)[-1]
    logging.info(f"Processing {schema_path} for subject: {subject_name}-value")

    # Find all imports
    try:
        imports = extract_imports(schema_path)
    except IOError as e:
        logging.error(f"Could not read file {schema_path}: {e}")
        return False

    try:
        with open(schema_path, "r", encoding="utf-8") as f:
            schema_content = f.read()
    except IOError as e:
        logging.error(f"Could not read file {schema_path}: {e}")
        return False

    payload = {"schemaType": "YANG", "schema": schema_content}

    if imports:
        logging.debug(f"Found imports: {', '.join(imports)}")
        references = [
            {"name": name, "subject": f"{name}-value", "version": 1}
            for name in imports
        ]
        payload["references"] = references

    url = f"{SCHEMA_REGISTRY_URL}/subjects/{subject_name}-value/versions"
    try:
        response = requests.post(
            url,
            headers={"Content-Type": "application/vnd.schemaregistry.v1+json"},
            data=json.dumps(payload),
            verify=False,
        )
        response.raise_for_status()
        logging.info(f"Successfully registered schema. Response: {response.json()}")
        return True
    except requests.exceptions.RequestException as e:
        logging.error(f"Failed to register schema: {e}")
        return False


def run_loop():
    while True:
        # files/yang are sorted by names
        files_to_process = sorted([p.stem for p in YANG_IMPORTED_DIR.glob("*.yang")])

        for filename in files_to_process:
            if not process_file(filename):
                logging.error(f"Command failed for {filename}. Exiting.")
                return

        logging.info("--- One Loop Done ---")
        time.sleep(2)


def main():
    if len(sys.argv) != 2:
        logging.error(f"Usage: python3 benchmark_yang_import.py https://schema-registry-url.")
        sys.exit(1)
    else:
        run_loop()


if __name__ == "__main__":
    main()

