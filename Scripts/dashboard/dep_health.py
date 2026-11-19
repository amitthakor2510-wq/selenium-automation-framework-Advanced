"""Are the things the framework depends on reachable? Selenium Grid, Ollama, ReportPortal, Appium, and the
Android emulator/device.

On demand only (the Console's "Check now" button) — the Console makes no network calls of its own at rest.
Targets come from the repository's own config files, never from the HTTP request, so a caller cannot point the
check anywhere. Each target gets ONE short GET to a fixed path built from the configured host and port only:
credentials, query strings and paths in the configured URL are dropped (and never shown), redirects are not
followed, the response body is not read, and link-local/multicast/unspecified addresses (cloud metadata etc.)
are refused. Any HTTP answer counts as "reachable" — the point is that something is listening and speaking HTTP.

The emulator/device is checked WITHOUT running adb (the Console runs no commands): `mobile.device.name` is read
and, when it is `emulator-<port>` (the emulator's console port on this machine) or `host:port` (a Genymotion /
Wi-Fi device), a plain TCP connect is made and closed at once — nothing is sent. A plain device name such as
"Samsung Galaxy S24" cannot be checked that way and says so.
"""
import re
import http.client
import ipaddress
import os
import socket
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlsplit

import config_files as cf

TIMEOUT_S = 2.0
CACHE_S = 5.0

TARGETS = (
    {"id": "grid", "name": "Selenium Grid", "file": "src/test/resources/config/global.properties",
     "key": "grid.url", "enabled_key": "grid.enabled", "path": "/status"},
    {"id": "ollama", "name": "Ollama (AI features)", "file": "src/test/resources/config/global.properties",
     "key": "ai.endpoint", "path": "/api/tags"},
    {"id": "reportportal", "name": "ReportPortal", "file": "src/test/resources/reportportal.properties",
     "key": "rp.endpoint", "path": "/"},
    {"id": "appium", "name": "Appium (mobile / emulator)", "file": "src/test/resources/config/mobile.properties",
     "key": "appium.server.url", "path": "/status"},
    {"id": "emulator", "name": "Android emulator / device", "file": "src/test/resources/config/mobile.properties",
     "key": "mobile.device.name", "kind": "tcp-device"},
)
_EMULATOR = re.compile(r"^emulator-(\d{4,5})$")
_HOSTPORT = re.compile(r"^([A-Za-z0-9.-]{1,253}):(\d{2,5})$")
_cache = {}
_lock = threading.Lock()


def _prop(repo_root, rel, key):
    try:
        return (cf.PropertiesFile(os.path.join(repo_root, *rel.split("/"))).get(key) or "").strip()
    except (OSError, ValueError):
        return ""


def _parse(url):
    """(scheme, host, port) for a plain http(s) URL, else None. Credentials/path/query are discarded."""
    try:
        u = urlsplit(url)
        host = u.hostname
        port = u.port
    except ValueError:
        return None
    if u.scheme not in ("http", "https") or not host:
        return None
    return u.scheme, host, port or (443 if u.scheme == "https" else 80)


def _refused_address(host, port):
    """Reason string when `host` resolves to an address the check must never touch, else None."""
    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError:
        return None   # unresolvable: the probe itself reports that
    for info in infos:
        ip = ipaddress.ip_address(info[4][0].split("%")[0])
        if ip.is_link_local or ip.is_multicast or ip.is_unspecified:
            return "address not allowed (link-local, multicast or unspecified)"
    return None


def _device_address(value):
    """(host, port) for a device name that maps to a TCP address, else None."""
    m = _EMULATOR.match(value)
    if m:
        return "127.0.0.1", int(m.group(1))
    m = _HOSTPORT.match(value)
    if m and 0 < int(m.group(2)) < 65536:
        return m.group(1), int(m.group(2))
    return None


def _probe_device(target, repo_root):
    name = _prop(repo_root, target["file"], target["key"])
    out = {"id": target["id"], "name": target["name"], "configured": bool(name), "enabled": None,
           "target": "", "reachable": None, "status": None, "ms": None, "note": ""}
    if not name:
        out["note"] = "not configured"
        return out
    addr = _device_address(name)
    if addr is None:
        out["note"] = "device name only — set mobile.device.name to emulator-5554 or host:port to check reachability"
        return out
    host, port = addr
    out["target"] = f"{host}:{port}"
    refused = _refused_address(host, port)
    if refused:
        out["note"] = refused
        return out
    started = time.monotonic()
    try:
        with socket.create_connection((host, port), timeout=TIMEOUT_S):
            pass
        out.update(reachable=True, ms=int((time.monotonic() - started) * 1000), note="ok")
    except OSError as e:
        out.update(reachable=False, ms=int((time.monotonic() - started) * 1000), note=f"no answer ({type(e).__name__})")
    return out


def _probe(target, repo_root):
    if target.get("kind") == "tcp-device":
        return _probe_device(target, repo_root)
    url = _prop(repo_root, target["file"], target["key"])
    enabled = None
    if target.get("enabled_key"):
        enabled = _prop(repo_root, target["file"], target["enabled_key"]).lower() == "true"
    out = {"id": target["id"], "name": target["name"], "configured": bool(url), "enabled": enabled,
           "target": "", "reachable": None, "status": None, "ms": None, "note": ""}
    if not url:
        out["note"] = "not configured"
        return out
    parsed = _parse(url)
    if parsed is None:
        out["note"] = "configured value is not a plain http(s) URL"
        return out
    scheme, host, port = parsed
    out["target"] = f"{host}:{port}"
    refused = _refused_address(host, port)
    if refused:
        out["note"] = refused
        return out
    conn_cls = http.client.HTTPSConnection if scheme == "https" else http.client.HTTPConnection
    started = time.monotonic()
    conn = conn_cls(host, port, timeout=TIMEOUT_S)
    try:
        conn.request("GET", target["path"], headers={"User-Agent": "automation-console-health", "Connection": "close"})
        resp = conn.getresponse()
        out.update(reachable=True, status=resp.status, ms=int((time.monotonic() - started) * 1000))
        out["note"] = "ok" if resp.status < 400 else f"answered HTTP {resp.status}"
    except (OSError, http.client.HTTPException) as e:
        out.update(reachable=False, ms=int((time.monotonic() - started) * 1000))
        out["note"] = f"no answer ({type(e).__name__})"
    finally:
        conn.close()
    return out


def check(repo_root, now=None):
    """Probe every target in parallel; results are cached for a few seconds so a double click costs nothing."""
    now = time.monotonic() if now is None else now
    with _lock:
        hit = _cache.get(repo_root)
        if hit and now - hit[0] < CACHE_S:
            return hit[1]
    with ThreadPoolExecutor(max_workers=len(TARGETS)) as pool:
        results = list(pool.map(lambda t: _probe(t, repo_root), TARGETS))
    payload = {"checked_at": int(time.time()), "targets": results}
    with _lock:
        _cache[repo_root] = (now, payload)
    return payload
