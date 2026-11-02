# Fixtures for semgrep-rules/dashboard-security.yml - run: semgrep --test semgrep-rules
# Lines annotated as ruleid must be flagged by that rule; lines annotated ok must not be.
import hashlib
import hmac
import os
import pickle
import ssl
import subprocess
import tempfile
from http.server import ThreadingHTTPServer

import yaml


def shell_true(cmd, argv):
    # ruleid: dashboard-subprocess-shell-true
    subprocess.run(cmd, shell=True)
    # ruleid: dashboard-subprocess-shell-true
    os.system(cmd)
    # ok: dashboard-subprocess-shell-true
    subprocess.Popen(argv, shell=False)
    # ok: dashboard-subprocess-shell-true
    subprocess.run(argv, check=True)


def tls():
    # ruleid: dashboard-tls-verification-disabled
    ctx = ssl._create_unverified_context()
    # ruleid: dashboard-tls-verification-disabled
    ctx.check_hostname = False
    # ok: dashboard-tls-verification-disabled
    return ssl.create_default_context(cafile="ca.pem")


def deserialize(blob, text):
    # ruleid: dashboard-unsafe-deserialization
    pickle.loads(blob)
    # ruleid: dashboard-unsafe-deserialization
    yaml.load(text)
    # ruleid: dashboard-unsafe-deserialization
    eval(text)
    # ok: dashboard-unsafe-deserialization
    yaml.safe_load(text)


def compare(given_password, expected_password, token, other):
    # ruleid: dashboard-secret-compared-with-equality
    if given_password == expected_password:
        return True
    # ruleid: dashboard-secret-compared-with-equality
    if token != other:
        return False
    # ok: dashboard-secret-compared-with-equality
    if token == "":
        return False
    # ok: dashboard-secret-compared-with-equality
    return hmac.compare_digest(given_password.encode(), expected_password.encode())


def hashes(data):
    # ruleid: dashboard-weak-hash-for-security
    hashlib.md5(data)
    # ruleid: dashboard-weak-hash-for-security
    hashlib.sha1(data)
    # ok: dashboard-weak-hash-for-security
    hashlib.md5(data, usedforsecurity=False)
    # ok: dashboard-weak-hash-for-security
    return hashlib.sha256(data)


def files(path):
    # ruleid: dashboard-insecure-temp-or-permissions
    tempfile.mktemp()
    # ruleid: dashboard-insecure-temp-or-permissions
    os.chmod(path, 0o777)
    # ok: dashboard-insecure-temp-or-permissions
    os.chmod(path, 0o600)


def serve(handler, host):
    # ruleid: dashboard-http-bind-all-interfaces-literal
    ThreadingHTTPServer(("0.0.0.0", 5057), handler)
    # ok: dashboard-http-bind-all-interfaces-literal
    return ThreadingHTTPServer((host, 5057), handler)


def paths(base, file_name, suite):
    # ruleid: dashboard-path-from-request-without-containment
    open(os.path.join(base, file_name))
    # ruleid: dashboard-path-from-request-without-containment
    open(f"{base}/{file_name}")
    # ok: dashboard-path-from-request-without-containment
    open("fixed/path.txt")
    # ok: dashboard-path-from-request-without-containment
    return suite
