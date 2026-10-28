"""
Validation for wizard specs: URL, locator (XPath/CSS), required fields, secret leaks.

Everything here is a pure function — no I/O besides loading wizard/forms/<type>.json — so it
is unit-tested directly (tests/test_validators.py). The same checks run live in the browser
(via /api/wizard/validate) and again, authoritatively, on save and on run start: the browser's
validation is a convenience, never the gate.

Result shape:  {"ok": bool, "errors": [{"path": "...", "message": "..."}], "warnings": [...]}
"""
import ipaddress
import json
import os
import re
from urllib.parse import urlparse

from auth import secrets_store
from reports import evidence_options, report_options
from specs import schema_check

_HERE = os.path.dirname(os.path.abspath(__file__))
FORMS_DIR = os.path.join(_HERE, "forms")
SCHEMA_PATH = os.path.join(os.path.dirname(_HERE), "specs", "spec_schema.json")
TYPES = ("ui", "api", "performance", "crawler", "accessibility")

MAX_CELL = 500
_CTRL = re.compile(r"[\x00-\x1f\x7f]")
_LOCATOR_RE = re.compile(r"^(id|name|css|xpath|class|linktext|partiallinktext|tag):(.+)$", re.I | re.S)
# references the keyword engine understands, plus the dashboard's own ${secret:NAME}
_PLACEHOLDER_RE = re.compile(r"\$\{(?:env|sys|config|i18n|var|secret):[^}]+\}")

_SECRET_KEY_RE = re.compile(r"(pass(word|wd)?|pwd|secret|token|api[_-]?key|authorization|bearer|credential)", re.I)
_SECRET_VALUE_PATTERNS = [
    ("AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b")),
    ("Slack token", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b")),
    ("JWT", re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b")),
    ("private key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("bearer token", re.compile(r"\bBearer\s+[A-Za-z0-9._~+/-]{20,}")),
    ("password in URL", re.compile(r"://[^/\s:@]+:[^/\s@]+@")),
]


def load_form(test_type):
    if test_type not in TYPES:
        raise KeyError(test_type)
    with open(os.path.join(FORMS_DIR, f"{test_type}.json"), encoding="utf-8") as f:
        return json.load(f)


def load_schema():
    with open(SCHEMA_PATH, encoding="utf-8") as f:
        return json.load(f)


# ── single-value validators (return list of messages) ───────────────────────────
def validate_url(value, *, required=True):
    if value in (None, ""):
        return ["required"] if required else []
    if not isinstance(value, str):
        return ["must be text"]
    if len(value) > 2048:
        return ["too long (max 2048)"]
    if _CTRL.search(value) or re.search(r"\s", value):
        return ["must not contain spaces or control characters"]
    try:
        u = urlparse(value)
        port = u.port  # raises ValueError for a bad port
    except ValueError:
        return ["not a valid URL"]
    if u.scheme not in ("http", "https"):
        return ["must start with http:// or https://"]
    if not u.hostname:
        return ["missing host name"]
    if u.username or u.password:
        return ["must not contain a user name or password — use a secret reference instead"]
    if port is not None and not (1 <= port <= 65535):
        return ["port out of range"]
    try:
        ip = ipaddress.ip_address(u.hostname)
        if ip.is_link_local or ip.is_multicast or ip.is_unspecified:
            return ["that address (link-local/metadata/unspecified) is not allowed as a test target"]
    except ValueError:
        pass  # a host name, not an IP literal
    return []


def _balanced(text, pairs):
    stack, quote = [], None
    closers = {c: o for o, c in pairs}
    for ch in text:
        if quote:
            if ch == quote:
                quote = None
            continue
        if ch in ("'", '"'):
            quote = ch
        elif ch in (o for o, _ in pairs):
            stack.append(ch)
        elif ch in closers:
            if not stack or stack.pop() != closers[ch]:
                return False
    return not stack and quote is None


def validate_locator(value, *, required=True):
    """Locators use the object-repository format  type:value  (see ObjectRepository)."""
    if value in (None, ""):
        return ["required"] if required else []
    if not isinstance(value, str):
        return ["must be text"]
    if len(value) > MAX_CELL:
        return [f"too long (max {MAX_CELL})"]
    if _CTRL.search(value):
        return ["must be a single line"]
    m = _LOCATOR_RE.match(value.strip())
    if not m:
        return ["use type:value, e.g. id:user-name, css:.btn, xpath://button — types: "
                "id, name, css, xpath, class, linktext, partiallinktext, tag"]
    kind, body = m.group(1).lower(), m.group(2).strip()
    if not body:
        return ["locator value is empty"]
    if kind in ("id", "name", "class", "tag") and re.search(r"\s", body):
        return [f"a {kind} locator can't contain spaces (use css: for compound selectors)"]
    if kind == "xpath":
        if not (body[0] in "/(." ):
            return ["an XPath should start with /, // , ( or ."]
        if not _balanced(body, [("[", "]"), ("(", ")")]):
            return ["XPath has unbalanced brackets, parentheses or quotes"]
    if kind == "css":
        if "{" in body or "}" in body:
            return ["a CSS selector can't contain { or }"]
        if not _balanced(body, [("[", "]"), ("(", ")")]):
            return ["CSS selector has unbalanced brackets, parentheses or quotes"]
    return []


def is_reference(value):
    return isinstance(value, str) and bool(_PLACEHOLDER_RE.fullmatch(value.strip()))


def validate_secret_ref(value, *, required=True, known_secrets=None):
    if value in (None, ""):
        return ["required"] if required else []
    parsed = secrets_store.parse_ref(value if isinstance(value, str) else "")
    if not parsed:
        return ["must be a reference like ${secret:NAME} or ${env:NAME} — never the secret itself"]
    return []


# ── secret leak scan ────────────────────────────────────────────────────────────
def find_secret_leaks(obj, path="$", key_hint=""):
    """[(path, reason)] for literal secrets anywhere in a spec. References are fine."""
    leaks = []
    if isinstance(obj, dict):
        for k, v in obj.items():
            leaks += find_secret_leaks(v, f"{path}.{k}", str(k))
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            leaks += find_secret_leaks(v, f"{path}[{i}]", key_hint)
    elif isinstance(obj, str) and obj.strip():
        text = obj.strip()
        for label, rx in _SECRET_VALUE_PATTERNS:
            if rx.search(text):
                leaks.append((path, f"looks like a {label} — replace it with a ${{secret:NAME}} reference"))
                break
        else:
            if (_SECRET_KEY_RE.search(key_hint) and not is_reference(text) and not _PLACEHOLDER_RE.search(text)
                    and key_hint not in ("authType",)):
                leaks.append((path, "this field looks like a credential — use ${secret:NAME} or ${env:NAME}"))
    return leaks


# ── form-driven validation ───────────────────────────────────────────────────────
def _visible(field, values):
    cond = field.get("showIf")
    return all(values.get(k) == v for k, v in cond.items()) if cond else True


def _validate_field(field, value, ctx):
    ftype, label = field["type"], field.get("label", field["id"])
    visible = ctx.get("_visible", True)
    required = field.get("required", False) or (bool(field.get("showIf")) and visible and ftype == "secret_ref")
    empty = value is None or value == "" or value == []
    if empty:
        return [f"{label}: required"] if required else []
    if ftype == "text":
        if not isinstance(value, str):
            return [f"{label}: must be text"]
        if _CTRL.search(value):
            return [f"{label}: must be a single line"]
        if len(value) > field.get("maxLength", 200):
            return [f"{label}: too long"]
        if field.get("pattern") and not re.fullmatch(field["pattern"], value):
            return [f"{label}: {field.get('help') or 'wrong format'}"]
        return []
    if ftype == "url":
        return [f"{label}: {m}" for m in validate_url(value, required=required)]
    if ftype in ("integer", "number"):
        ok = isinstance(value, int) if ftype == "integer" else isinstance(value, (int, float))
        if not ok or isinstance(value, bool):
            return [f"{label}: must be a {'whole number' if ftype == 'integer' else 'number'}"]
        if "min" in field and value < field["min"]:
            return [f"{label}: must be at least {field['min']}"]
        if "max" in field and value > field["max"]:
            return [f"{label}: must be at most {field['max']}"]
        return []
    if ftype == "select":
        return [] if value in field["options"] else [f"{label}: pick one of {', '.join(field['options'])}"]
    if ftype == "multiselect":
        if not isinstance(value, list) or not all(v in field["options"] for v in value):
            return [f"{label}: pick from {', '.join(field['options'])}"]
        return []
    if ftype == "checkbox":
        return [] if isinstance(value, bool) else [f"{label}: must be on or off"]
    if ftype == "site":
        allowed = ctx.get("sites_for", {}).get(field.get("siteSuites"), ctx.get("sites", []))
        if value not in allowed:
            return [f"{label}: '{value}' isn't available" + (f" (choose from {', '.join(allowed)})" if allowed else "")]
        return []
    if ftype == "secret_ref":
        return [f"{label}: {m}" for m in validate_secret_ref(value, required=required)]
    return []


def _validate_steps(form, steps):
    rules, errs = form["stepKeywords"], []
    if not isinstance(steps, list) or not steps:
        return [("details.steps", "add at least one step")]
    if len(steps) > 100:
        return [("details.steps", "too many steps (max 100)")]
    for i, s in enumerate(steps, 1):
        p = f"details.steps[{i - 1}]"
        if not isinstance(s, dict):
            errs.append((p, f"step {i}: invalid"))
            continue
        kw = s.get("keyword")
        rule = rules.get(kw)
        if not rule:
            errs.append((p + ".keyword", f"step {i}: choose an action"))
            continue
        for col, key in (("locator", "locator"), ("data", "data"), ("expected", "expected")):
            val, need = (s.get(key) or ""), rule[col]
            if not isinstance(val, str):
                errs.append((f"{p}.{key}", f"step {i}: {key} must be text"))
                continue
            if len(val) > MAX_CELL:
                errs.append((f"{p}.{key}", f"step {i}: {key} too long"))
            elif _CTRL.search(val):
                errs.append((f"{p}.{key}", f"step {i}: {key} must be a single line"))
            elif need == "req" and not val.strip():
                errs.append((f"{p}.{key}", f"step {i}: {kw} needs {key}"))
            elif need == "no" and val.strip():
                errs.append((f"{p}.{key}", f"step {i}: {kw} doesn't use {key}"))
        loc = s.get("locator") or ""
        if loc.strip():
            for m in validate_locator(loc):
                errs.append((f"{p}.locator", f"step {i}: {m}"))
        dtype, data = rule.get("dataType"), (s.get("data") or "").strip()
        if dtype and data and not _PLACEHOLDER_RE.search(data):
            try:
                n = float(data)
                if dtype == "integer" and (n != int(n) or n < 0):
                    raise ValueError
                if n < 0 or n > 3600:
                    raise ValueError
            except ValueError:
                errs.append((f"{p}.data", f"step {i}: {kw} needs a number"
                                          + (" (whole, 0 or more)" if dtype == "integer" else " of seconds (0-3600)")))
        # a password typed in plain text into a field that is obviously a password field
        if kw in ("TYPE", "SET_TEXT") and re.search(r"pass(word|wd)?|pwd|secret|token", loc, re.I) \
                and data and not _PLACEHOLDER_RE.search(data):
            errs.append((f"{p}.data", f"step {i}: this looks like a password field — use ${{secret:NAME}} or ${{env:NAME}}"))
    return errs


def _validate_requests(form, requests):
    errs = []
    if not isinstance(requests, list) or not requests:
        return [("details.requests", "add at least one request")]
    if len(requests) > 100:
        return [("details.requests", "too many requests (max 100)")]
    for i, r in enumerate(requests, 1):
        p = f"details.requests[{i - 1}]"
        if not isinstance(r, dict):
            errs.append((p, f"request {i}: invalid"))
            continue
        method = (r.get("method") or "").upper()
        if method not in form["methods"]:
            errs.append((p + ".method", f"request {i}: method must be one of {', '.join(form['methods'])}"))
        path = r.get("path") or ""
        if not isinstance(path, str) or not path.startswith("/") or path.startswith("//") or "://" in path \
                or _CTRL.search(path) or re.search(r"\s", path) or ".." in path.split("?")[0].split("/") or len(path) > 500:
            errs.append((p + ".path", f"request {i}: path must start with a single / (e.g. /posts/1) — no host, spaces or .."))
        status = r.get("expectedStatus")
        if not (isinstance(status, int) and not isinstance(status, bool) and 100 <= status <= 599):
            errs.append((p + ".expectedStatus", f"request {i}: expected status must be 100-599"))
        name = r.get("name") or ""
        if name and (not isinstance(name, str) or len(name) > 100 or _CTRL.search(name)):
            errs.append((p + ".name", f"request {i}: name must be one line, max 100 characters"))
        contains = r.get("bodyContains") or ""
        if contains and (not isinstance(contains, str) or len(contains) > 200 or _CTRL.search(contains)):
            errs.append((p + ".bodyContains", f"request {i}: 'must contain' must be one line, max 200 characters"))
        body = r.get("body")
        if body not in (None, ""):
            if method in ("GET",):
                errs.append((p + ".body", f"request {i}: GET requests don't have a body"))
            elif not isinstance(body, str) or len(body) > 10000:
                errs.append((p + ".body", f"request {i}: body must be JSON text (max 10000 characters)"))
            else:
                try:
                    json.loads(body)
                except ValueError:
                    errs.append((p + ".body", f"request {i}: body is not valid JSON"))
    return errs


def validate_spec(spec, ctx=None):
    """Full validation of a spec dict. ctx: {"sites": [...], "sites_for": {"perf": [...], "accessibility": [...]},
    "secret_names": [...] | None}. Never raises on bad input — returns the error list."""
    ctx = ctx or {}
    errors, warnings = [], []

    def err(path, msg):
        errors.append({"path": path, "message": msg})

    if not isinstance(spec, dict):
        return {"ok": False, "errors": [{"path": "$", "message": "spec must be an object"}], "warnings": []}
    for m in schema_check.check(spec, load_schema()):
        path, _, msg = m.partition(": ")
        err(path, msg)
    test_type = spec.get("type")
    if test_type not in TYPES:
        return {"ok": False, "errors": errors or [{"path": "type", "message": "unknown test type"}], "warnings": warnings}
    form = load_form(test_type)

    for section in ("target", "details"):
        values = spec.get(section) if isinstance(spec.get(section), dict) else {}
        known = {f["id"] for f in form[section]}
        for k in values:
            if k not in known:
                err(f"{section}.{k}", "unknown field")
        for field in form[section]:
            fctx = dict(ctx, _visible=_visible(field, values))
            if not fctx["_visible"]:
                continue
            value = values.get(field["id"])
            if field["type"] == "steps":
                for path, msg in _validate_steps(form, value):
                    err(path, msg)
            elif field["type"] == "requests":
                for path, msg in _validate_requests(form, value):
                    err(path, msg)
            else:
                for msg in _validate_field(field, value, fctx):
                    err(f"{section}.{field['id']}", msg)
            if field["type"] == "secret_ref" and fctx["_visible"] and value and ctx.get("secret_names") is not None:
                parsed = secrets_store.parse_ref(value)
                if parsed and parsed[0] == "secret" and parsed[1] not in ctx["secret_names"]:
                    warnings.append({"path": f"{section}.{field['id']}",
                                     "message": f"secret '{parsed[1]}' is not stored yet — the run will fail until an admin adds it"})

    reports = spec.get("reports") if isinstance(spec.get("reports"), dict) else {}
    for k, v in reports.items():
        if v and k not in report_options.available(test_type):
            err(f"reports.{k}", f"not available for {test_type} tests")
    evidence = spec.get("evidence") if isinstance(spec.get("evidence"), dict) else {}
    if evidence and not evidence_options.applies(test_type):
        if any(evidence.get(k) not in (None, False, "on-failure") for k in ("video", "aiNote", "screenshots")):
            err("evidence", f"screenshots/video/AI note only apply to browser-driven tests, not {test_type}")

    for path, reason in find_secret_leaks({k: spec.get(k) for k in ("target", "details", "name") if k in spec}):
        if not any(e["path"] == path.replace("$.", "") for e in errors):
            err(path.replace("$.", ""), reason)

    return {"ok": not errors, "errors": errors, "warnings": warnings}
