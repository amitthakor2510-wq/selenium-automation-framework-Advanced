"""
Encrypted storage for logins/tokens used by tests (so a spec never contains one).

A spec only ever holds a REFERENCE:  ${secret:NAME}  (stored here)  or  ${env:NAME}
(read from the dashboard process's environment). The runner resolves references into the
environment of the one test process it starts; resolved values are never written to
spec.json, properties.generated, run.log metadata, or any API response, and the API only
lists secret NAMES.

Encryption is Fernet (AES-128-CBC + HMAC) from the `cryptography` package, keyed by
DASHBOARD_SECRET_KEY (generate with:  python3 -c "from cryptography.fernet import Fernet;
print(Fernet.generate_key().decode())").  Fails closed: without the package or the key the
store refuses to read or write anything — it never falls back to plaintext or home-made
crypto. `${env:NAME}` references still work without either.
"""
import json
import os
import re
import tempfile
import threading

NAME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,63}$")
REF_RE = re.compile(r"^\$\{(secret|env):([A-Za-z][A-Za-z0-9_]{0,63})\}$")
_lock = threading.Lock()


class SecretsUnavailable(RuntimeError):
    pass


def _path():
    return os.environ.get("DASHBOARD_SECRETS_FILE", os.path.join(os.path.dirname(os.path.abspath(__file__)), "secrets.enc.json"))


def _fernet():
    key = os.environ.get("DASHBOARD_SECRET_KEY", "").strip()
    if not key:
        raise SecretsUnavailable("DASHBOARD_SECRET_KEY is not set — the secret store is disabled "
                                 "(use ${env:NAME} references instead)")
    try:
        from cryptography.fernet import Fernet  # optional dependency, deliberately not required
    except ImportError as e:
        raise SecretsUnavailable("the 'cryptography' package is not installed — the secret store is disabled "
                                 "(pip install cryptography, or use ${env:NAME} references)") from e
    try:
        return Fernet(key.encode("utf-8"))
    except (ValueError, TypeError) as e:
        raise SecretsUnavailable("DASHBOARD_SECRET_KEY is not a valid Fernet key") from e


def available():
    try:
        _fernet()
        return True
    except SecretsUnavailable:
        return False


def _load_raw():
    p = _path()
    if not os.path.isfile(p):
        return {}
    with open(p, encoding="utf-8") as f:
        data = json.load(f)
    return data if isinstance(data, dict) else {}


def _save_raw(data):
    p = _path()
    os.makedirs(os.path.dirname(p), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(p), suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    os.chmod(tmp, 0o600)
    os.replace(tmp, p)


def set_secret(name, value):
    if not NAME_RE.match(name or ""):
        raise ValueError("secret name must start with a letter: letters, digits, underscore (max 64)")
    if not value:
        raise ValueError("secret value is empty")
    f = _fernet()
    with _lock:
        data = _load_raw()
        data[name] = f.encrypt(value.encode("utf-8")).decode("ascii")
        _save_raw(data)


def delete_secret(name):
    with _lock:
        data = _load_raw()
        if name not in data:
            raise ValueError(f"no such secret: {name}")
        del data[name]
        _save_raw(data)


def list_names():
    """Names only — values never leave this module except through resolve()."""
    with _lock:
        return sorted(_load_raw())


def get_secret(name):
    f = _fernet()
    with _lock:
        token = _load_raw().get(name)
    if token is None:
        raise KeyError(name)
    return f.decrypt(token.encode("ascii")).decode("utf-8")


def parse_ref(value):
    """('secret'|'env', NAME) for a well-formed reference, else None."""
    m = REF_RE.match(value or "")
    return (m.group(1), m.group(2)) if m else None


def resolve(ref, environ=None):
    """Value behind a reference. Raises KeyError/SecretsUnavailable if it can't be resolved."""
    parsed = parse_ref(ref)
    if not parsed:
        raise ValueError("not a secret reference")
    kind, name = parsed
    if kind == "env":
        env = os.environ if environ is None else environ
        if name not in env:
            raise KeyError(f"environment variable {name} is not set")
        return env[name]
    return get_secret(name)
