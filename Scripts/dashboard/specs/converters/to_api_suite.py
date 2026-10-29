"""API spec -> an api-test.sh spec file (JSON is valid YAML, so json.dumps output is written as .yml)."""
import json
import os

from specs.converters import common
from wizard import validators


def _auth(t, collect):
    kind = t.get("authType") or "none"
    if kind == "bearer" and t.get("authToken"):
        return {"type": "bearer", "token": common.rewrite_refs(t["authToken"], collect)}
    if kind == "basic" and t.get("authUser") and t.get("authPassword"):
        return {"type": "basic", "username": common.rewrite_refs(t["authUser"], collect),
                "password": common.rewrite_refs(t["authPassword"], collect)}
    if kind == "apiKey" and t.get("apiKeyName") and t.get("apiKeyValue"):
        return {"type": "apiKey", "name": t["apiKeyName"], "value": common.rewrite_refs(t["apiKeyValue"], collect),
                "in": t.get("apiKeyIn") or "header"}
    return None


def convert(spec, inputs_dir):
    t, d = spec["target"], spec["details"]
    collect = common.new_collect()
    tests = []
    for r in d["requests"]:
        method = r["method"].lower()
        case = {"name": r.get("name") or f"{r['method'].upper()} {r['path']}", method: r["path"],
                "expect": {"status": r["expectedStatus"]}}
        if r.get("bodyContains"):
            case["expect"]["bodyContains"] = [r["bodyContains"]]
        if r.get("body"):
            case["body"] = json.loads(r["body"])
        tests.append(case)
    defaults = {"maxTimeMs": int(t.get("maxTimeMs", 5000))}
    if int(t.get("retries") or 0) > 0:
        defaults["retry"] = {"count": int(t["retries"])}
    if t.get("insecureTls"):
        defaults["insecure"] = True
    doc = {"name": d["suiteName"], "baseUrl": t["baseUrl"], "defaults": defaults, "tests": tests}
    auth = _auth(t, collect)
    if auth:
        doc["auth"] = auth
    headers = validators.parse_headers(t.get("extraHeaders") or "")
    if headers:
        doc["headers"] = {name: common.rewrite_refs(value, collect) for name, value in headers}
    path = common.write_text(os.path.join(inputs_dir, "api-spec.yml"), json.dumps(doc, indent=2) + "\n")
    return {"files": [path], "spec_file": path, "properties": {}, "base_url": t["baseUrl"],
            "secret_env": collect["secrets"], "env_passthrough": sorted(collect["env"])}
