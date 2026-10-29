"""OpenAPI spec -> an `api-test.sh --openapi URL` run.

The generated tests read their credentials from fixed environment variables (API_TOKEN, API_USER,
API_PASSWORD, API_KEY — see OpenApiSpecGenerator). A stored ${secret:NAME} is handed to the run under
that variable name; a ${env:NAME} is copied from the dashboard's own environment under it. Neither
value ever appears on the command line or in a file.
"""
from auth import secrets_store

# auth type -> [(variable the generated tests read, form field that holds the reference)]
AUTH_VARS = {"bearer": [("API_TOKEN", "authToken")],
             "basic": [("API_USER", "authUser"), ("API_PASSWORD", "authPassword")],
             "apiKey": [("API_KEY", "authKey")]}


def convert(spec, inputs_dir=None):
    t, d = spec["target"], spec["details"]
    secret_env, alias = {}, {}
    for var, field in AUTH_VARS.get(t.get("authType") or "none", []):
        parsed = secrets_store.parse_ref(t.get(field) or "")
        if not parsed:
            continue
        kind, name = parsed
        (secret_env if kind == "secret" else alias)[var] = name
    options = {}
    if t.get("baseUrl"):
        options["base_url"] = t["baseUrl"]
    if d.get("negative"):
        options["negative"] = True
    if d.get("maxTimeMs"):
        options["max_time"] = int(d["maxTimeMs"])
    if int(d.get("retries") or 0) > 0:
        options["retry"] = int(d["retries"])
    return {"files": [], "properties": {}, "openapi_url": t["openapiUrl"], "options": options,
            "secret_env": secret_env, "env_alias": alias, "env_passthrough": []}
