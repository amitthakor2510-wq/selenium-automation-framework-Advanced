"""Helpers shared by the converters: secret-reference handling and safe file writing."""
import os
import re
from auth import secrets_store

_REF_ANY = re.compile(r"\$\{(secret|env):([A-Za-z][A-Za-z0-9_]{0,63})\}")


def env_name_for_secret(name):
    """Environment variable the runner will use to hand a stored secret to the test process."""
    return "WIZARD_SECRET_" + name.upper()


def rewrite_refs(text, collect):
    """Replace ${secret:NAME} with ${env:WIZARD_SECRET_NAME} (what the keyword/API engines
    understand) and record what the runner must provide.
    collect = {"secrets": {ENVVAR: NAME}, "env": set(NAME)}"""
    if not isinstance(text, str):
        return text

    def sub(m):
        kind, name = m.group(1), m.group(2)
        if kind == "secret":
            var = env_name_for_secret(name)
            collect["secrets"][var] = name
            return "${env:" + var + "}"
        collect["env"].add(name)
        return m.group(0)

    return _REF_ANY.sub(sub, text)


def new_collect():
    return {"secrets": {}, "env": set()}


def write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    return path


def props_escape(value):
    """Escape a value for a java .properties file."""
    return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")


def secure_ref(value):
    return secrets_store.parse_ref(value)
