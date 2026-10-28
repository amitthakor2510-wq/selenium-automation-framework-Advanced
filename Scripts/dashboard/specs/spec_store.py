"""
Save, load and version wizard templates (re-runnable test setups).

Layout:  <data>/templates/<slug>/v0001.json, v0002.json, ...   (newest = highest number)
Every save is a NEW version; nothing is overwritten, so a template can be rolled back by
loading an older version and saving it again. All writes are atomic (temp file + rename).
Specs hold secret REFERENCES only (see auth/secrets_store.py) — save() refuses a spec that
fails validation, including any literal secret it detects.
"""
import json
import os
import re
import tempfile
import threading
from datetime import datetime, timezone

from wizard import validators

MAX_VERSIONS = 50
_SLUG_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,59}$")
_lock = threading.Lock()


def data_dir():
    return os.environ.get("DASHBOARD_DATA_DIR", os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data"))


def templates_dir():
    return os.path.join(data_dir(), "templates")


def slugify(name):
    slug = re.sub(r"[^a-z0-9]+", "-", (name or "").lower()).strip("-")[:60].strip("-")
    if not _SLUG_RE.fullmatch(slug):
        raise ValueError("template name must contain letters or digits")
    return slug


def _tdir(slug):
    if not _SLUG_RE.fullmatch(slug or ""):
        raise ValueError("invalid template id")
    return os.path.join(templates_dir(), slug)


def _versions(slug):
    d = _tdir(slug)
    if not os.path.isdir(d):
        return []
    out = []
    for fn in os.listdir(d):
        m = re.fullmatch(r"v(\d{4,})\.json", fn)
        if m:
            out.append(int(m.group(1)))
    return sorted(out)


def _atomic_write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")
    os.replace(tmp, path)


def save(spec, author, ctx=None):
    """Validate, then store as the next version. Returns {"slug", "version"}.
    Raises ValueError (message lists the problems) if the spec is invalid."""
    result = validators.validate_spec(spec, ctx)
    if not result["ok"]:
        raise ValueError("; ".join(f"{e['path']}: {e['message']}" for e in result["errors"][:5]))
    slug = slugify(spec["name"])
    with _lock:
        versions = _versions(slug)
        nxt = (versions[-1] + 1) if versions else 1
        stored = dict(spec)
        stored["meta"] = {"version": nxt, "savedBy": author,
                          "savedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
        _atomic_write_json(os.path.join(_tdir(slug), f"v{nxt:04d}.json"), stored)
        for old in versions[:max(0, len(versions) + 1 - MAX_VERSIONS)]:
            try:
                os.remove(os.path.join(_tdir(slug), f"v{old:04d}.json"))
            except OSError:
                pass
    return {"slug": slug, "version": nxt}


def load(slug, version=None):
    versions = _versions(slug)
    if not versions:
        raise FileNotFoundError(f"no such template: {slug}")
    v = versions[-1] if version is None else int(version)
    if v not in versions:
        raise FileNotFoundError(f"template {slug} has no version {v}")
    with open(os.path.join(_tdir(slug), f"v{v:04d}.json"), encoding="utf-8") as f:
        return json.load(f)


def list_templates():
    root = templates_dir()
    if not os.path.isdir(root):
        return []
    out = []
    for slug in sorted(os.listdir(root)):
        if not _SLUG_RE.fullmatch(slug):
            continue
        try:
            spec = load(slug)
        except (FileNotFoundError, ValueError, json.JSONDecodeError):
            continue
        meta = spec.get("meta", {})
        out.append({"slug": slug, "name": spec.get("name"), "type": spec.get("type"),
                    "version": meta.get("version"), "savedBy": meta.get("savedBy"), "savedAt": meta.get("savedAt"),
                    "versions": _versions(slug)})
    return out


def delete(slug):
    d = _tdir(slug)
    versions = _versions(slug)
    if not versions:
        raise FileNotFoundError(f"no such template: {slug}")
    with _lock:
        for v in versions:
            os.remove(os.path.join(d, f"v{v:04d}.json"))
        try:
            os.rmdir(d)
        except OSError:
            pass
