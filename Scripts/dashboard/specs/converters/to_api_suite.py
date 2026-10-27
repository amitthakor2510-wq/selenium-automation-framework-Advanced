"""API spec -> an api-test.sh spec file (JSON is valid YAML, so json.dumps output is written as .yml)."""
import json
import os
import re

from specs.converters import common


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
    doc = {"name": d["suiteName"], "baseUrl": t["baseUrl"],
           "defaults": {"maxTimeMs": int(t.get("maxTimeMs", 5000))}, "tests": tests}
    if t.get("authType") == "bearer" and t.get("authToken"):
        doc["auth"] = {"type": "bearer", "token": common.rewrite_refs(t["authToken"], collect)}
    path = common.write_text(os.path.join(inputs_dir, "api-spec.yml"), json.dumps(doc, indent=2) + "\n")
    return {"files": [path], "spec_file": path, "properties": {}, "base_url": t["baseUrl"],
            "secret_env": collect["secrets"], "env_passthrough": sorted(collect["env"])}
