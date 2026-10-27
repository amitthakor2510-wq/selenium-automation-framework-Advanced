"""
Tiny JSON-Schema subset checker (stdlib only — the console deliberately has no pip
dependencies). Supports exactly what specs/spec_schema.json uses: type, required, properties,
additionalProperties (false), enum, pattern, minimum, maximum, minLength, maxLength, items.
"""
import re


def _is_type(value, t):
    if t == "object":
        return isinstance(value, dict)
    if t == "array":
        return isinstance(value, list)
    if t == "string":
        return isinstance(value, str)
    if t == "boolean":
        return isinstance(value, bool)
    if t == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if t == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    return False


def check(value, schema, path="$"):
    """List of 'path: message' strings (empty = valid)."""
    errors = []
    t = schema.get("type")
    if t and not _is_type(value, t):
        return [f"{path}: expected {t}"]
    if "enum" in schema and value not in schema["enum"]:
        errors.append(f"{path}: must be one of {', '.join(map(str, schema['enum']))}")
    if isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            errors.append(f"{path}: too short")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            errors.append(f"{path}: too long")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            errors.append(f"{path}: wrong format")
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            errors.append(f"{path}: must be >= {schema['minimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            errors.append(f"{path}: must be <= {schema['maximum']}")
    if isinstance(value, dict):
        for req in schema.get("required", []):
            if req not in value:
                errors.append(f"{path}.{req}: required")
        props = schema.get("properties", {})
        for k, v in value.items():
            if k in props:
                errors += check(v, props[k], f"{path}.{k}")
            elif schema.get("additionalProperties") is False:
                errors.append(f"{path}.{k}: unknown field")
    if isinstance(value, list) and "items" in schema:
        for i, item in enumerate(value):
            errors += check(item, schema["items"], f"{path}[{i}]")
    return errors
