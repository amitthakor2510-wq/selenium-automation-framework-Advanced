"""
The ONLY commands the runner can ever execute.

There is no way to pass a command, a shell fragment or an arbitrary argument through this
module: each test type maps to ONE fixed program + fixed leading arguments, and everything
variable is a -Dkey=value whose KEY is on a per-type allowlist and whose VALUE matches a strict
pattern (or, for file paths, resolves inside the run's own workspace). Commands are returned as
argv lists and run with shell=False, so there is no shell to inject into either.

Anything not listed here raises NotAllowed — the runner treats that as a hard error, never
as something to work around.
"""
import os
import re
import shutil

BOOL = re.compile(r"^(true|false)$")
INT = re.compile(r"^\d{1,6}$")
NUM = re.compile(r"^\d{1,6}(\.\d{1,3})?$")
NAME = re.compile(r"^[A-Za-z0-9_]{1,40}$")
URL = re.compile(r"^https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%\[\]-]{1,2000}$")
CASES = re.compile(r"^[A-Za-z0-9_,]{1,300}$")
IMPACT = re.compile(r"^(minor|moderate|serious|critical)(,(minor|moderate|serious|critical)){0,3}$")
BROWSERS = re.compile(r"^(chrome|firefox|edge)$")
PATH = "path-in-workspace"


class NotAllowed(Exception):
    pass


_COMMON = {
    "site": NAME, "browser": BROWSERS, "headless": BOOL, "retry.count": re.compile(r"^[0-5]$"),
    "url": URL, "video.enabled": BOOL, "video.keep.on.pass": BOOL,
    "ai.exceptionAnalysis.enabled": BOOL, "report.pdf.enabled": BOOL,
}
_PERF = {"perf.threads": INT, "perf.rampUpSeconds": INT, "perf.iterations": INT,
         "perf.maxP99Millis": INT, "perf.maxErrorRatePercent": NUM}
_CRAWLER = {"crawler.startUrl": URL, "crawler.maxPages": INT, "crawler.maxDepth": INT, "crawler.a11y.enabled": BOOL}

ALLOWED_PROPERTIES = {
    "ui": dict(_COMMON, **{"wizard.keyword.repo": PATH, "wizard.keyword.script": PATH, "wizard.keyword.cases": CASES}),
    "performance": dict(_COMMON, **_PERF),
    "crawler": dict(_COMMON, **_CRAWLER),
    "accessibility": dict(_COMMON, **{"a11y.enabled": BOOL, "a11y.failOn": IMPACT}),
    "api": {},  # api-test.sh takes flags, not properties
}

# Fixed programs. "{mvn}" is resolved to a Maven launcher found on this machine (never user input).
FIXED_PREFIX = {
    "ui": ["{mvn}", "-B", "-ntp", "test"],
    "performance": ["{mvn}", "-B", "-ntp", "-Pperf", "test", "-Dgroups=perf"],
    "accessibility": ["{mvn}", "-B", "-ntp", "test"],
    "crawler": ["{mvn}", "-B", "-q", "-Pbug-crawler", "exec:java@bug-crawler"],
    "api": ["bash", "Scripts/api-test.sh"],
}

_SUITE_RE = re.compile(r"^testng-suites/[A-Za-z0-9_-]+\.xml$")


def find_maven(repo_root):
    found = shutil.which("mvn")
    if found:
        return found
    wrapper = os.path.join(repo_root, "mvnw")
    if os.path.isfile(wrapper) and os.access(wrapper, os.X_OK):
        return wrapper
    raise NotAllowed("Maven not found (install mvn or keep the executable ./mvnw)")


def _inside(path, root):
    real, base = os.path.realpath(path), os.path.realpath(root)
    return real == base or real.startswith(base + os.sep)


def _check_property(test_type, key, value, workspace):
    rule = ALLOWED_PROPERTIES[test_type].get(key)
    if rule is None:
        raise NotAllowed(f"property '{key}' is not allowed for {test_type} runs")
    value = str(value)
    if rule == PATH:
        if not os.path.isabs(value) or not _inside(value, workspace) or not os.path.isfile(value):
            raise NotAllowed(f"'{key}' must be a file inside the run workspace")
    elif not rule.fullmatch(value):
        raise NotAllowed(f"value for '{key}' is not allowed")


def _check_suite(suite, repo_root, workspace):
    if _SUITE_RE.fullmatch(suite):
        if not os.path.isfile(os.path.join(repo_root, suite)):
            raise NotAllowed(f"suite file does not exist: {suite}")
        return suite
    if os.path.isabs(suite) and suite.endswith("suite.xml") and _inside(suite, workspace) and os.path.isfile(suite):
        return suite
    raise NotAllowed("suite file must be a testng-suites/*.xml file or the run's generated suite.xml")


def build_command(test_type, plan, repo_root, workspace):
    """argv for a run. plan = converter output ({"properties", "suite_xml", "spec_file", "base_url", ...})."""
    if test_type not in FIXED_PREFIX:
        raise NotAllowed(f"unknown test type: {test_type}")
    if test_type == "api":
        spec_file = plan.get("spec_file", "")
        if not (os.path.isabs(spec_file) and _inside(spec_file, workspace) and os.path.isfile(spec_file)):
            raise NotAllowed("API spec file must be inside the run workspace")
        base = plan.get("base_url", "")
        if not URL.fullmatch(base):
            raise NotAllowed("API base URL is not allowed")
        return ["bash", "Scripts/api-test.sh", "--spec", spec_file, "--base-url", base]

    argv = [find_maven(repo_root) if a == "{mvn}" else a for a in FIXED_PREFIX[test_type]]
    if plan.get("suite_xml"):
        argv.append("-DsuiteXmlFile=" + _check_suite(plan["suite_xml"], repo_root, workspace))
    for key in sorted(plan.get("properties", {})):
        _check_property(test_type, key, plan["properties"][key], workspace)
        argv.append(f"-D{key}={plan['properties'][key]}")
    return argv


# Environment handed to the test process: a short allowlist, never the dashboard's own secrets.
BASE_ENV = ("PATH", "HOME", "USER", "LANG", "LC_ALL", "JAVA_HOME", "TMPDIR", "TZ", "DISPLAY")
_ENV_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,63}$")


def build_env(plan, resolved_secrets, environ=None):
    environ = os.environ if environ is None else environ
    env = {k: environ[k] for k in BASE_ENV if k in environ}
    extra = [n.strip() for n in environ.get("DASHBOARD_RUN_ENV_PASSTHROUGH", "").split(",") if n.strip()]
    for name in list(plan.get("env_passthrough", [])) + extra:
        if not _ENV_NAME.fullmatch(name) or name.startswith("DASHBOARD_"):
            raise NotAllowed(f"environment variable '{name}' can't be passed to a run")
        if name in environ:
            env[name] = environ[name]
    for var, value in resolved_secrets.items():
        if not var.startswith("WIZARD_SECRET_"):
            raise NotAllowed("unexpected secret variable name")
        env[var] = value
    return env


def redact(text, secret_values):
    """Mask any resolved secret value that shows up in log text."""
    for v in secret_values:
        if v and len(v) >= 4:
            text = text.replace(v, "***")
    return text
