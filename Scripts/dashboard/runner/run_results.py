"""
Turns a finished run into something a non-technical person can read: a verdict, pass/fail counts,
the failing checks, plain-language "what to try next" hints, and links to the reports.

  summarize(...)  -> dict      written to <run>/results.json by the runner, served at /api/runs/<id>/results
  diagnose(text)  -> [hint]    pattern-matches the tail of run.log against the usual causes

Counts come from the JUnit/Surefire XML that Maven writes to target/surefire-reports. Those files are
shared by every run (Maven writes into the repo's target/), so only files modified since THIS run
started are read: a run that produced no reports must never be credited with the previous run's results.

Stdlib only. Nothing here executes anything or touches the network.
"""
import glob
import json
import os
import re
import xml.etree.ElementTree as ET

MAX_XML_BYTES = 20 * 1024 * 1024
MAX_FAILURES = 50
MAX_MESSAGE = 400
LOG_TAIL_BYTES = 60_000
# Files written a moment before the run's recorded start (clock granularity, slow filesystems) still count.
MTIME_SLACK_SECONDS = 2

REPORT_LABELS = {
    "allure-results": "Allure raw results",
    "allure-report": "Allure report",
    "extent-reports": "Extent HTML report",
    "surefire-reports": "Test result files (XML)",
    "pdf-reports": "PDF report",
    "api-report": "API test report",
    "perf-reports": "Performance report",
    "crawler": "Crawler findings",
    "mobile-crawler": "Mobile crawler findings",
    "apidocs": "Javadoc",
    "dependency-check": "Dependency vulnerability report",
    "pit-reports": "Mutation testing report",
    "tia": "Test impact analysis",
}

# (regex on the log, hint). First matches are shown first; at most MAX_HINTS are returned.
_HINTS = [
    (r"UnknownHostException|Name or service not known|nodename nor servname|getaddrinfo|ERR_NAME_NOT_RESOLVED",
     "The address could not be found. Check the web address for typos and that this computer can reach it."),
    (r"Connection refused|ECONNREFUSED|ERR_CONNECTION_REFUSED|Failed to connect to",
     "Nothing answered at that address. Make sure the application (or Appium / Selenium Grid) is running and the port is right."),
    (r"ERR_CERT|SSLHandshakeException|PKIX path|certificate verify failed|unable to find valid certification path",
     "The site's security certificate was rejected. Use the correct https address, or test against a server with a valid certificate."),
    (r"SessionNotCreatedException|This version of ChromeDriver|unable to discover open pages|cannot find Chrome binary|"
     r"Unable to find a matching set of capabilities",
     "The browser could not be started. Check that the chosen browser is installed and up to date, or try another browser."),
    (r"NoSuchElementException|no such element|Unable to locate element|TimeoutException.*(visib|clickab|presence)",
     "A button or field the test looks for was not found on the page. Re-check the locators (id/css/xpath) and that the page "
     "finished loading; adding a 'wait for visible' step usually helps."),
    (r"Appium|4723|NoSuchDriverException|Could not start a new session|UiAutomator2",
     "The mobile device could not be reached. Start the Appium server and the emulator/phone, then check the app package or app file."),
    (r"\b401\b|Unauthorized|\b403\b|Forbidden",
     "The server refused the request. Check the login or token (saved secret) and that it has permission for these calls."),
    (r"\b404\b|Not Found",
     "A page or endpoint was not found. Check the address and the paths you entered."),
    (r"\b429\b|Too Many Requests",
     "The server is rate-limiting the test. Lower the load, add pauses, or ask the owner to allow the test machine."),
    (r"OutOfMemoryError|Java heap space",
     "The test ran out of memory. Lower the load (fewer users / pages) and run again."),
    (r"Could not resolve dependencies|Could not transfer artifact|Connection to https://repo\.maven|Non-resolvable parent",
     "Maven could not download what it needs. Check the internet connection (or proxy) on this computer."),
    (r"COMPILATION ERROR|cannot find symbol|package .* does not exist",
     "The framework code did not compile. This is a code problem, not a problem with your test settings."),
    (r"Address already in use|BindException",
     "A required port is already in use. Close the other program using it, then run again."),
    (r"No such file or directory|FileNotFoundException",
     "A file the run needs was not found. Check file paths in the form (app file, spec file)."),
    (r"Permission denied",
     "Access to a file or folder was denied. Check file permissions for the user running the dashboard."),
    (r"Maven not found|mvn: command not found|mvnw: command not found",
     "Maven is not installed or not on the PATH. Install Maven, or keep the executable ./mvnw in the project."),
]
MAX_HINTS = 4


def diagnose(text):
    """Plain-language hints for the usual causes, from log text. Always safe on any input."""
    if not isinstance(text, str) or not text:
        return []
    out = []
    for pattern, hint in _HINTS:
        if re.search(pattern, text, re.I) and hint not in out:
            out.append(hint)
        if len(out) >= MAX_HINTS:
            break
    return out


def _clip(s, n=MAX_MESSAGE):
    s = " ".join((s or "").split())
    return s if len(s) <= n else s[: n - 1] + "…"


def _parse_junit_file(path):
    """(passed, failed, skipped, [failure dicts], seconds) for one JUnit XML file; None if unreadable."""
    try:
        if os.path.getsize(path) > MAX_XML_BYTES:
            return None
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return None
    passed = failed = skipped = 0
    failures, seconds = [], 0.0
    for tc in root.iter("testcase"):
        try:
            seconds += float(tc.get("time") or 0)
        except ValueError:
            pass
        bad = tc.find("failure")
        if bad is None:
            bad = tc.find("error")
        if bad is not None:
            failed += 1
            failures.append({"test": tc.get("name") or "(unnamed)", "class": (tc.get("classname") or "").rsplit(".", 1)[-1],
                             "message": _clip(bad.get("message") or (bad.text or ""))})
        elif tc.find("skipped") is not None:
            skipped += 1
        else:
            passed += 1
    return passed, failed, skipped, failures, seconds


def read_counts(surefire_dirs, since=None):
    """Aggregate every TEST-*.xml in the given report folder(s) modified at/after `since` (epoch seconds).
    Returns None when there is nothing (new enough) to read, else
    {"total","passed","failed","skipped","failures":[...],"testSeconds"}."""
    if isinstance(surefire_dirs, str):
        surefire_dirs = [surefire_dirs]
    files = [f for d in surefire_dirs for f in glob.glob(os.path.join(d, "TEST-*.xml"))]
    if since is not None:
        files = [f for f in files if _mtime(f) >= since - MTIME_SLACK_SECONDS]
    totals = {"passed": 0, "failed": 0, "skipped": 0}
    failures, seconds, read_any = [], 0.0, False
    for f in sorted(files):
        parsed = _parse_junit_file(f)
        if parsed is None:
            continue
        read_any = True
        p, fl, s, fails, secs = parsed
        totals["passed"] += p
        totals["failed"] += fl
        totals["skipped"] += s
        failures += fails
        seconds += secs
    if not read_any:
        return None
    total = sum(totals.values())
    if total == 0:
        return None
    return {"total": total, **totals, "failures": failures[:MAX_FAILURES], "testSeconds": round(seconds, 1)}


def _mtime(path):
    try:
        return os.path.getmtime(path)
    except OSError:
        return 0.0


def tail_text(path, max_bytes=LOG_TAIL_BYTES):
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as f:
            f.seek(max(0, size - max_bytes))
            return f.read().decode("utf-8", "replace")
    except OSError:
        return ""


def _headline(state, counts, code, seconds, test_like=True):
    secs = f" in {seconds:.0f} s" if seconds and seconds >= 1 else ""
    if state == "cancelled":
        return "The run was cancelled before it finished."
    if state == "timeout":
        return "The run took longer than the allowed time and was stopped."
    if state == "error":
        return "The dashboard could not start or finish this run."
    if counts:
        total, failed = counts["total"], counts["failed"]
        if failed == 0 and state == "passed":
            skipped = f" ({counts['skipped']} skipped)" if counts["skipped"] else ""
            return f"All {counts['passed']} checks passed{skipped}{secs}."
        if failed:
            return f"{failed} of {total} checks failed{secs}."
        return f"The run stopped with an error (exit code {code}) although no individual check failed."
    if state == "passed":
        return f"Finished successfully{secs}." if not test_like else f"Finished{secs}, but no test results were recorded."
    return f"The run stopped with an error (exit code {code}) before any test results were recorded."


def verdict_for(state, counts, test_like=True):
    """passed | failed | no-tests | cancelled | timeout | error.
    'no-tests': the command exited 0 but a test-type run recorded no results (shown as a warning, not green)."""
    if state in ("cancelled", "timeout", "error"):
        return state
    if state == "passed":
        return "no-tests" if (test_like and counts is None) else "passed"
    return "failed"


def report_links(run_id, copied):
    out = []
    for name in copied or []:
        out.append({"name": name, "label": REPORT_LABELS.get(name, name),
                    "link": f"/api/runs/{run_id}/files/report/{name}.zip"})
    return out


def summarize(run_id, spec, state, code, seconds, copied, surefire_dirs=None, since=None, log_path=None, test_like=True):
    """Build the results dict. `test_like` False for housekeeping features (no tests expected)."""
    counts = read_counts(surefire_dirs, since) if (surefire_dirs and test_like) else None
    verdict = verdict_for(state, counts, test_like)
    hints = []
    if state in ("failed", "error", "timeout"):
        evidence = (tail_text(log_path) if log_path else "") + "\n" + "\n".join(f["message"] for f in (counts or {}).get("failures", []))
        hints = diagnose(evidence)
        if state == "timeout":
            hints.insert(0, "Raise 'Stop the run after (minutes)' on the Reports step, or run a smaller test.")
    if state == "failed" and counts and not counts["failed"]:
        hints.append("The command failed although no individual check did (a setup or build problem). Open the full log and read the last lines.")
    if counts and counts["failed"] and not hints:
        hints.append("Open the failing checks below. If a failure looks random, re-run once: the Retry setting can absorb flaky pages.")
    if state == "passed" and test_like and counts is None:
        hints.append("The command finished but no test results were recorded. If you expected checks to run, "
                     "open the full log to see why none were found.")
    return {
        "runId": run_id,
        "name": (spec or {}).get("name", ""),
        "type": (spec or {}).get("type", ""),
        "verdict": verdict,
        "headline": _headline(state, counts, code, seconds, test_like),
        "counts": ({k: counts[k] for k in ("total", "passed", "failed", "skipped")} if counts else None),
        "failures": (counts or {}).get("failures", []),
        "durationSeconds": round(seconds, 1) if seconds is not None else None,
        "exitCode": code,
        "hints": hints,
        "reports": report_links(run_id, copied),
    }


def write(run_dir, results):
    path = os.path.join(run_dir, "results.json")
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
        f.write("\n")
    os.replace(tmp, path)
    return path


def read(run_dir):
    """The saved results, or None (old runs that predate this file, or a run that has not finished)."""
    try:
        with open(os.path.join(run_dir, "results.json"), encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else None
    except (OSError, ValueError):
        return None


def fallback(status):
    """Results for a run that has no results.json: still queued/running, or finished before this file existed."""
    state = (status or {}).get("state", "unknown")
    live = state in ("queued", "running")
    return {"runId": (status or {}).get("id", ""), "name": (status or {}).get("name", ""), "type": (status or {}).get("type", ""),
            "verdict": "running" if live else state, "counts": (status or {}).get("counts"), "failures": [], "hints": [],
            "headline": ("The run is still in progress." if live else (status or {}).get("headline") or f"Run {state}."),
            "durationSeconds": None, "exitCode": (status or {}).get("exitCode"),
            "reports": report_links((status or {}).get("id", ""), (status or {}).get("reports"))}
