"""
Whole-config export/import — download the current sites + tests + groups
+ run.only as one JSON file, share it (Slack, a PR description, a
teammate's inbox), and have someone else load it back exactly. This is
the shareable counterpart to presets.json: a preset is a maintainer-
curated, checked-in combination; a snapshot is whatever one person's
config looked like at one moment, meant to be handed to a specific
person for a specific reason ("here's exactly what I had enabled when
this bug reproduced").

Snapshot shape:
    {
      "framework_dashboard_snapshot": 1,
      "generated_at": "2026-09-21T12:00:00Z",
      "sites": {"demoqa": true, "saucedemo": false, ...},
      "tests": {"LoginTest": true, "BrokenLinksImagesTest": false, ...},
      "groups": {"perf": false, ...},
      "run_only": "LoginTest,SampleTest"
    }

Applying a snapshot never fails partway through silently: every site,
test, and group name is validated (same character allow-list
config_files.py's individual setters use) BEFORE anything is written,
and unknown/malformed entries are collected and returned rather than
raising — so "some of this snapshot doesn't match this checkout" is a
warning in the response, not a half-applied file.
"""
import re
from datetime import datetime, timezone

SCHEMA_VERSION = 1
_SITE_NAME_RE = re.compile(r"[A-Za-z0-9_]+")
_TEST_NAME_RE = re.compile(r"[^\s=#]+")
_GROUP_NAME_RE = re.compile(r"[A-Za-z0-9_-]+")


def build(sites, test_config):
    """sites: cf.read_sites() result. test_config: cf.read_test_config() result."""
    tests = {}
    for section in test_config["sections"]:
        for t in section["tests"]:
            tests[t["name"]] = t["enabled"]
    return {
        "framework_dashboard_snapshot": SCHEMA_VERSION,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "sites": {s["name"]: s["enabled"] for s in sites},
        "tests": tests,
        "groups": {g["name"]: g["enabled"] for g in test_config["groups"]},
        "run_only": test_config.get("run_only", ""),
    }


def validate(snapshot):
    """
    Returns (clean, problems) — `clean` is the snapshot with anything
    malformed removed, `problems` is a list of human-readable strings
    describing what was dropped and why. Never raises on bad input: a
    hand-edited or corrupted snapshot file should produce a clear
    "here's what I ignored" response, not a 500.
    """
    if not isinstance(snapshot, dict):
        return None, ["not a JSON object"]

    problems = []
    clean = {"sites": {}, "tests": {}, "groups": {}, "run_only": ""}

    version = snapshot.get("framework_dashboard_snapshot")
    if version != SCHEMA_VERSION:
        problems.append(f"unexpected/missing schema version ({version!r}, expected {SCHEMA_VERSION}) — proceeding anyway")

    for name, enabled in (snapshot.get("sites") or {}).items():
        if isinstance(name, str) and _SITE_NAME_RE.fullmatch(name) and isinstance(enabled, bool):
            clean["sites"][name] = enabled
        else:
            problems.append(f"skipped invalid site entry: {name!r}")

    for name, enabled in (snapshot.get("tests") or {}).items():
        if isinstance(name, str) and _TEST_NAME_RE.fullmatch(name) and isinstance(enabled, bool):
            clean["tests"][name] = enabled
        else:
            problems.append(f"skipped invalid test entry: {name!r}")

    for name, enabled in (snapshot.get("groups") or {}).items():
        if isinstance(name, str) and _GROUP_NAME_RE.fullmatch(name) and isinstance(enabled, bool):
            clean["groups"][name] = enabled
        else:
            problems.append(f"skipped invalid group entry: {name!r}")

    run_only = snapshot.get("run_only", "")
    if isinstance(run_only, str) and re.fullmatch(r"[A-Za-z0-9_.,*\s]*", run_only):
        clean["run_only"] = run_only
    elif run_only not in ("", None):
        problems.append(f"skipped invalid run_only value: {run_only!r}")

    return clean, problems


def apply(pipeline_config_path, test_config_path, clean):
    """Writes the validated snapshot: one set_many() call per file (see
    PropertiesFile.set_many) — at most two file writes total, however
    many individual settings the snapshot contains."""
    from config_files import PropertiesFile  # local import: avoids a cycle at module load time

    if clean["sites"]:
        updates = [(f"site.{name}.enabled", "true" if v else "false") for name, v in clean["sites"].items()]
        PropertiesFile(pipeline_config_path).set_many(updates)

    test_updates = [(f"test.{name}.enabled", "true" if v else "false") for name, v in clean["tests"].items()]
    test_updates += [(f"group.{name}.enabled", "true" if v else "false") for name, v in clean["groups"].items()]
    if "run_only" in clean:
        cleaned_run_only = ",".join(p.strip() for p in clean["run_only"].split(",") if p.strip())
        test_updates.append(("run.only", cleaned_run_only))
    if test_updates:
        PropertiesFile(test_config_path).set_many(test_updates)
