"""
Per-user accounts and roles for the dashboard (tester < lead < admin).

Backwards compatible: the original single shared login ("dashboard" +
DASHBOARD_PASSWORD) keeps working and is treated as an admin. Per-user mode
turns on only when auth/users.json exists; then every request is checked against
it, and the audit log records "<user>@<ip>" instead of just the IP.

users.json holds PBKDF2 hashes only (never passwords) and is gitignored. Manage it with:

    python3 Scripts/dashboard/auth/users.py add alice tester     (prompts for the password)
    python3 Scripts/dashboard/auth/users.py list
    python3 Scripts/dashboard/auth/users.py passwd alice
    python3 Scripts/dashboard/auth/users.py remove alice

Stdlib only.
"""
import getpass
import hashlib
import hmac
import json
import os
import re
import secrets
import sys
import tempfile
import threading
import time

ROLES = ("tester", "lead", "admin")
_RANK = {r: i for i, r in enumerate(ROLES)}
_NAME_RE = re.compile(r"^[a-z][a-z0-9_.-]{1,31}$")
PBKDF2_ITERATIONS = 210_000
LEGACY_USER = "dashboard"

# permission -> minimum role
PERMISSIONS = {
    "spec.view": "tester",
    "spec.save": "tester",
    "run.view": "tester",
    "run.start": "tester",
    "run.cancel": "tester",       # testers: own runs only (enforced by the caller)
    "run.cancel.any": "lead",
    "template.delete": "lead",
    "ci.trigger": "lead",
    "config.edit": "lead",        # the original site/test toggles, once per-user mode is on
    "secrets.manage": "admin",
}

_DEFAULT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "users.json")
_lock = threading.Lock()
_cache = {}  # sha256(user:password) -> (role, expires_at); never holds plaintext
_CACHE_TTL = 300


def users_path():
    return os.environ.get("DASHBOARD_USERS_FILE", _DEFAULT_PATH)


def per_user_mode(path=None):
    return os.path.isfile(path or users_path())


def allowed(role, permission):
    need = PERMISSIONS.get(permission)
    return need is not None and role in _RANK and _RANK[role] >= _RANK[need]


def _hash(password, salt, iterations=PBKDF2_ITERATIONS):
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations).hex()


def load(path=None):
    path = path or users_path()
    if not os.path.isfile(path):
        return {}
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    users = data.get("users", {}) if isinstance(data, dict) else {}
    return users if isinstance(users, dict) else {}


def _save(users, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump({"users": users}, f, indent=2)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def add_user(name, role, password, path=None):
    path = path or users_path()
    if not _NAME_RE.match(name or ""):
        raise ValueError("username must be 2-32 chars: lowercase letters, digits, . _ -")
    if name == LEGACY_USER:
        raise ValueError(f"'{LEGACY_USER}' is reserved for the DASHBOARD_PASSWORD login")
    if role not in ROLES:
        raise ValueError(f"role must be one of {', '.join(ROLES)}")
    if len(password or "") < 10:
        raise ValueError("password must be at least 10 characters")
    with _lock:
        users = load(path)
        salt = secrets.token_bytes(16)
        users[name] = {"role": role, "salt": salt.hex(), "iterations": PBKDF2_ITERATIONS,
                       "hash": _hash(password, salt)}
        _save(users, path)
        _cache.clear()


def remove_user(name, path=None):
    path = path or users_path()
    with _lock:
        users = load(path)
        if name not in users:
            raise ValueError(f"no such user: {name}")
        del users[name]
        _save(users, path)
        _cache.clear()


def verify(name, password, path=None):
    """Role for valid credentials, else None. Constant-time compare; a dummy hash is computed
    for unknown users so response time doesn't reveal which usernames exist."""
    path = path or users_path()
    key = hashlib.sha256(f"{path}\0{name}\0{password}".encode("utf-8")).hexdigest()
    now = time.time()
    with _lock:
        hit = _cache.get(key)
        if hit and hit[1] > now:
            return hit[0]
    rec = load(path).get(name)
    if not rec:
        _hash(password, b"\0" * 16)
        return None
    try:
        calc = _hash(password, bytes.fromhex(rec["salt"]), int(rec.get("iterations", PBKDF2_ITERATIONS)))
    except (KeyError, ValueError):
        return None
    if not hmac.compare_digest(calc, rec.get("hash", "")):
        return None
    role = rec.get("role")
    if role not in ROLES:
        return None
    with _lock:
        _cache[key] = (role, now + _CACHE_TTL)
    return role


def _cli(argv):
    if len(argv) < 2 or argv[1] not in ("add", "list", "passwd", "remove"):
        print(__doc__)
        return 2
    cmd = argv[1]
    try:
        if cmd == "list":
            for n, rec in sorted(load().items()):
                print(f"{n:20} {rec.get('role')}")
        elif cmd == "remove":
            remove_user(argv[2])
            print("removed", argv[2])
        else:
            name = argv[2]
            role = argv[3] if cmd == "add" else load().get(name, {}).get("role")
            if role is None:
                raise ValueError(f"no such user: {name}")
            pw = getpass.getpass("Password: ")
            if pw != getpass.getpass("Repeat   : "):
                raise ValueError("passwords differ")
            add_user(name, role, pw)
            print("saved", name, f"({role})")
    except (ValueError, IndexError) as e:
        print("error:", e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(_cli(sys.argv))
