import os
import sys
import tempfile

DASH = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if DASH not in sys.path:
    sys.path.insert(0, DASH)

CTX = {"sites": ["saucedemo", "demoqa"], "sites_for": {"perf": ["demoqa"], "accessibility": ["demoqa"]}, "secret_names": None}


def ui_spec(**over):
    spec = {"version": 1, "name": "Login smoke", "type": "ui",
            "target": {"site": "saucedemo", "browser": "chrome", "headless": True, "retryCount": 0},
            "details": {"scenarioName": "Valid login", "steps": [
                {"keyword": "NAVIGATE", "locator": "", "data": "https://www.saucedemo.com", "expected": "", "description": "open"},
                {"keyword": "TYPE", "locator": "id:user-name", "data": "standard_user", "expected": ""},
                {"keyword": "TYPE", "locator": "id:password", "data": "${secret:SAUCE_PW}", "expected": ""},
                {"keyword": "CLICK", "locator": "id:login-button", "data": "", "expected": ""},
                {"keyword": "VERIFY_URL_CONTAINS", "locator": "", "data": "", "expected": "inventory"}]},
            "reports": {"allure": True, "extent": True, "summary": True, "pdf": False},
            "evidence": {"screenshots": "on-failure", "video": False, "aiNote": False},
            "run": {"timeoutMinutes": 5}}
    spec.update(over)
    return spec


def api_spec():
    return {"version": 1, "name": "Posts API", "type": "api",
            "target": {"baseUrl": "https://jsonplaceholder.typicode.com", "authType": "bearer", "authToken": "${env:API_TOKEN}", "maxTimeMs": 3000},
            "details": {"suiteName": "Posts suite", "requests": [
                {"method": "GET", "path": "/posts/1", "expectedStatus": 200, "name": "one post", "bodyContains": "userId"},
                {"method": "POST", "path": "/posts", "expectedStatus": 201, "body": "{\"title\": \"x\"}"}]},
            "reports": {"allure": False, "summary": True}, "evidence": {}, "run": {"timeoutMinutes": 5}}


def perf_spec():
    return {"version": 1, "name": "Home perf", "type": "performance", "target": {"site": "demoqa"},
            "details": {"threads": 5, "rampUpSeconds": 2, "iterations": 3, "maxP99Millis": 4000, "maxErrorRatePercent": 1.5},
            "reports": {"summary": True}, "evidence": {}, "run": {"timeoutMinutes": 10}}


def crawler_spec():
    return {"version": 1, "name": "Site crawl", "type": "crawler", "target": {"browser": "chrome", "headless": True},
            "details": {"startUrl": "https://example.com", "maxPages": 10, "maxDepth": 2, "a11yEnabled": True},
            "reports": {"summary": True}, "evidence": {"screenshots": "on-failure", "video": False, "aiNote": False}, "run": {"timeoutMinutes": 10}}


def a11y_spec():
    return {"version": 1, "name": "A11y scan", "type": "accessibility", "target": {"site": "demoqa", "browser": "chrome", "headless": True},
            "details": {"failOn": ["serious", "critical"]}, "reports": {"summary": True, "allure": True, "extent": False, "pdf": False},
            "evidence": {"screenshots": "on-failure", "video": False, "aiNote": False}, "run": {"timeoutMinutes": 10}}


def tmpdir():
    return tempfile.TemporaryDirectory()


def read(path, encoding="utf-8"):
    """Read a whole text file and close it (a bare open(...).read() leaks a handle -> ResourceWarning)."""
    with open(path, encoding=encoding) as f:
        return f.read()


def write(path, text="", encoding="utf-8"):
    """Write (or create empty) a text file and close it."""
    with open(path, "w", encoding=encoding) as f:
        f.write(text)
