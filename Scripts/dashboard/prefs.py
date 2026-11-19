"""Per-login Console preferences (theme, density, landing tab, auto-refresh interval).

Stored in <data dir>/preferences.json (the same DASHBOARD_DATA_DIR as the run history and templates), keyed by
the signed-in user name, so a person gets the same Console on every browser. Every value is checked against a
fixed list of choices on the way in AND on the way out: a hand-edited or damaged file can never feed the UI
anything it did not offer. Preferences are a personal convenience, not configuration — any signed-in role,
including a read-only viewer, may change its own.
"""
import json
import os
import tempfile
import threading

from specs import spec_store

FILE_NAME = "preferences.json"
MAX_USERS = 200
MAX_NAME = 64

DEFAULTS = {"theme": "system", "density": "comfortable", "default_tab": "overview", "refresh_s": 15,
            # Threshold alerts (0 = off): a banner when the latest run falls below / drifts past these.
            "alert_pass_pct": 0, "alert_coverage_pct": 0, "alert_slowdown_pct": 0}
CHOICES = {
    "theme": ("system", "light", "dark"),
    "density": ("comfortable", "compact"),
    "default_tab": ("overview", "config", "results", "activity"),
    "refresh_s": (0, 15, 30, 60, 300),    # 0 = automatic refresh off
    "alert_pass_pct": (0, 80, 90, 95, 100),          # alert when the pass rate is below this
    "alert_coverage_pct": (0, 50, 60, 70, 80, 90),   # ... when core line coverage is below this
    "alert_slowdown_pct": (0, 25, 50, 100, 200),     # ... when the run is this much slower than the recent median
}
_INT_KEYS = {k for k, v in CHOICES.items() if isinstance(v[0], int)}
_lock = threading.Lock()


def prefs_path():
    return os.path.join(spec_store.data_dir(), FILE_NAME)


def _ok(key, value):
    if key in _INT_KEYS:
        return isinstance(value, int) and not isinstance(value, bool) and value in CHOICES[key]
    return isinstance(value, str) and value in CHOICES[key]


def _load():
    try:
        with open(prefs_path(), encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def _write(data):
    path = prefs_path()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".prefs-", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f, separators=(",", ":"))
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def get(user):
    """The user's preferences with defaults filled in; unknown or invalid stored values fall back to defaults."""
    stored = _load().get(user) if isinstance(user, str) else None
    stored = stored if isinstance(stored, dict) else {}
    return {k: (stored[k] if k in stored and _ok(k, stored[k]) else DEFAULTS[k]) for k in DEFAULTS}


def describe(user):
    """get() plus `saved`: the keys this user has explicitly stored. The Console uses it so that a browser's
    existing local theme/density choice is only replaced by a value the person really saved on the server,
    never by a default."""
    stored = _load().get(user) if isinstance(user, str) else None
    stored = stored if isinstance(stored, dict) else {}
    out = get(user)
    out["saved"] = sorted(k for k in DEFAULTS if k in stored and _ok(k, stored[k]))
    return out


def update(user, patch):
    """Validate and merge `patch` (a dict of known keys) into the user's preferences; returns the result.

    Raises ValueError (-> HTTP 400) for an unknown key, a value that is not one of the offered choices, an empty
    patch, or a user name that cannot be stored."""
    if not isinstance(user, str) or not user or len(user) > MAX_NAME:
        raise ValueError("cannot store preferences for this user")
    if not isinstance(patch, dict) or not patch:
        raise ValueError("send at least one preference to change")
    for key, value in patch.items():
        if key not in CHOICES:
            raise ValueError(f"unknown preference: {key!r}")
        if not _ok(key, value):
            raise ValueError(f"{key!r} must be one of {list(CHOICES[key])}")
    with _lock:
        data = _load()
        if user not in data and len(data) >= MAX_USERS:
            raise ValueError("too many users with stored preferences")
        merged = dict(data.get(user) if isinstance(data.get(user), dict) else {})
        merged.update(patch)
        data[user] = {k: merged[k] for k in DEFAULTS if k in merged and _ok(k, merged[k])}
        _write(data)
    return get(user)
