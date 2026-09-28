"""Validate the Phase 1 draft locally: python3 docs/workers-api-v1/check.py (requires jsonschema)."""

import json
from pathlib import Path
from urllib.parse import urldefrag

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


root = Path(__file__).parent
schemas = {path.name: json.loads(path.read_text()) for path in (root / "schemas").glob("*.json")}
registry = Registry()
for schema in schemas.values():
    Draft202012Validator.check_schema(schema)
    registry = registry.with_resource(schema["$id"], Resource.from_contents(schema))

examples = json.loads((root / "examples.json").read_text())
for expected in ("valid", "invalid"):
    for case in examples[expected]:
        name, _, fragment = case["schema"].partition("#")
        ref = schemas[name]["$id"] + ("#" + fragment if fragment else "")
        errors = list(Draft202012Validator({"$ref": ref}, registry=registry, format_checker=FormatChecker()).iter_errors(case["value"]))
        if bool(errors) == (expected == "valid"):
            raise AssertionError(f"{case['name']}: expected {expected}, got {[error.message for error in errors]}")

api = json.loads((root / "openapi.json").read_text())
assert api["openapi"] == "3.1.0"
operation_ids = set()


def check_refs(node):
    if isinstance(node, list):
        for value in node:
            check_refs(value)
    elif isinstance(node, dict):
        if isinstance(node.get("operationId"), str):
            assert node["operationId"] not in operation_ids, "duplicate operationId"
            operation_ids.add(node["operationId"])
        if "$ref" in node:
            file, fragment = urldefrag(node["$ref"])
            target = json.loads((root / file).read_text()) if file else api
            for part in fragment.lstrip("/").split("/"):
                if part:
                    target = target[part.replace("~1", "/").replace("~0", "~")]
        for value in node.values():
            check_refs(value)


check_refs(api)
print(f"Validated {len(schemas)} schemas, {len(examples['valid'])} valid and {len(examples['invalid'])} invalid examples, {len(api['paths'])} OpenAPI paths and {len(operation_ids)} operations.")
