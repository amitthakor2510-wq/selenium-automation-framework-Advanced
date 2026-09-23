"""
Line-precise reader/writer for pipeline-config.properties and
test-config.properties — the two on/off files documented in
docs/configuration.md.

Deliberately does NOT use java.util.Properties-style "load into a dict,
mutate, dump back out" round-tripping: both files are hand-written with
extensive header comments and per-site/per-test explanations, and a
naive rewrite would either lose every comment or reorder everything.
Instead this finds the exact line for a key with a regex and replaces
only that line's value, byte-for-byte identical file otherwise — the
same "smallest possible diff" approach the framework's own memory/CI
conventions favor.

Both read and write funnel through one PropertiesFile class so the same
key-matching logic (and its edge cases: trailing comments, inline
whitespace, a key that doesn't exist yet) is only written once.
"""
import re
import tempfile
import os
import threading

# One process-wide lock per file path — the dashboard is single-process
# but multi-threaded (ThreadingHTTPServer), and two browser tabs toggling
# different switches at the same moment must not interleave writes.
_locks = {}
_locks_guard = threading.Lock()


def _lock_for(path):
    with _locks_guard:
        lock = _locks.get(path)
        if lock is None:
            lock = threading.Lock()
            _locks[path] = lock
        return lock


class PropertiesFile:
    """Reads/writes one properties file, preserving everything except the
    single value being changed."""

    def __init__(self, path):
        self.path = path

    def read_text(self):
        with open(self.path, encoding="utf-8") as f:
            return f.read()

    def get(self, key):
        """Value of `key=value` (not a comment), or None if the key isn't present/enabled."""
        pattern = re.compile(
            r"^[ \t]*" + re.escape(key) + r"[ \t]*=[ \t]*([^\r\n#]*)", re.MULTILINE)
        m = pattern.search(self.read_text())
        return m.group(1).strip() if m else None

    def set(self, key, value):
        """Single-key convenience wrapper around set_many — see its docstring
        for the atomicity/locking guarantee."""
        self.set_many([(key, value)])

    def set_many(self, updates):
        """
        Applies every (key, value) pair in `updates` in ONE read + ONE
        atomic write, under the same per-file lock `set()` uses — so a
        bulk operation (e.g. "disable every test in this section", 40
        keys) is one file write and one moment where a concurrent reader
        could observe a torn state, not 40. Existing `key=...` lines have
        only their value replaced (comments/prose around them untouched,
        same as `set()`); a key that doesn't exist yet is appended at the
        end, in the order given.
        """
        with _lock_for(self.path):
            text = self.read_text()
            appended = []
            for key, value in updates:
                pattern = re.compile(
                    r"^([ \t]*" + re.escape(key) + r"[ \t]*=)[ \t]*[^\r\n#]*",
                    re.MULTILINE)
                new_line = r"\g<1>" + value
                if pattern.search(text):
                    text = pattern.sub(new_line, text, count=1)
                else:
                    appended.append(f"{key}={value}")
            if appended:
                if not text.endswith("\n"):
                    text += "\n"
                text += "\n".join(appended) + "\n"
            self._atomic_write(text)

    def _atomic_write(self, text):
        directory = os.path.dirname(os.path.abspath(self.path)) or "."
        fd, tmp_path = tempfile.mkstemp(prefix=".dashboard-", dir=directory)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(text)
            os.replace(tmp_path, self.path)
        except Exception:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
            raise


# ── pipeline-config.properties ────────────────────────────────────────

_SITE_KEY_RE = re.compile(r"^site\.([A-Za-z0-9_]+)\.enabled[ \t]*=[ \t]*([^\r\n#]*)", re.MULTILINE)
_SITE_TYPE_RE = re.compile(r"^site\.([A-Za-z0-9_]+)\.type[ \t]*=[ \t]*([^\r\n#]*)", re.MULTILINE)


def read_sites(path):
    """[{"name": "demoqa", "enabled": True, "type": "browser"}, ...] in file order."""
    text = PropertiesFile(path).read_text()
    types = {m.group(1): m.group(2).strip() for m in _SITE_TYPE_RE.finditer(text)}
    sites = []
    for m in _SITE_KEY_RE.finditer(text):
        name = m.group(1)
        sites.append({
            "name": name,
            "enabled": m.group(2).strip().lower() == "true",
            "type": types.get(name, "browser"),
        })
    return sites


def read_raw(path):
    """Full file text, for the dashboard's read-only "raw file" viewer."""
    return PropertiesFile(path).read_text()


def set_site_enabled(path, site_name, enabled):
    if not re.fullmatch(r"[A-Za-z0-9_]+", site_name or ""):
        raise ValueError(f"invalid site name: {site_name!r}")
    PropertiesFile(path).set(f"site.{site_name}.enabled", "true" if enabled else "false")


# ── test-config.properties ────────────────────────────────────────────

# Matches this file's own generated shape: a "# ── <section> ──" comment
# line, one blank-separated block of "test.<Name>.enabled=<bool>" lines
# below it. Falls back to "(ungrouped)" for anything outside a section
# (e.g. a line a user added by hand).
_SECTION_RE = re.compile(r"^#[ \t]*──[ \t]*(.+?)[ \t]*──*[ \t]*$")
_TEST_KEY_RE = re.compile(r"^test\.([^\s=]+)\.enabled[ \t]*=[ \t]*([^\r\n#]*)")
_GROUP_KEY_RE = re.compile(r"^group\.([^\s=]+)\.enabled[ \t]*=[ \t]*([^\r\n#]*)")
_RUN_ONLY_RE = re.compile(r"^run\.only[ \t]*=[ \t]*([^\r\n#]*)")


def read_test_config(path):
    """
    {
      "run_only": "LoginTest,SampleTest" | "",
      "sections": [{"title": "demoqa — UI tests (...)", "tests": [{"name": "...", "enabled": true}, ...]}],
      "groups": [{"name": "perf", "enabled": false}],
    }
    Tests outside any "# ── ... ──" section land under a synthetic
    "(ungrouped)" section rather than being dropped.
    """
    text = PropertiesFile(path).read_text()
    sections = []
    current = {"title": "(ungrouped)", "tests": []}
    groups = []
    run_only = ""
    seen_section = False

    for line in text.splitlines():
        sm = _SECTION_RE.match(line)
        if sm:
            if current["tests"]:
                sections.append(current)
            current = {"title": sm.group(1), "tests": []}
            seen_section = True
            continue
        tm = _TEST_KEY_RE.match(line)
        if tm:
            current["tests"].append({
                "name": tm.group(1),
                "enabled": tm.group(2).strip().lower() != "false",  # fails open, same as TestSelection
            })
            continue
        gm = _GROUP_KEY_RE.match(line)
        if gm:
            groups.append({"name": gm.group(1), "enabled": gm.group(2).strip().lower() != "false"})
            continue
        rm = _RUN_ONLY_RE.match(line)
        if rm:
            run_only = rm.group(1).strip()

    if current["tests"] or not seen_section:
        sections.append(current)

    return {"run_only": run_only, "sections": sections, "groups": groups}


def set_tests_enabled(path, test_names, enabled):
    """
    Bulk version of set_test_enabled — one read + one atomic write for the
    whole list (see PropertiesFile.set_many), used by the "enable/disable
    all in this section" buttons so toggling e.g. 35 demoqa UI tests is
    one file write, not 35.
    """
    value = "true" if enabled else "false"
    updates = []
    for name in test_names:
        if not re.fullmatch(r"[^\s=#]+", name or ""):
            raise ValueError(f"invalid test name: {name!r}")
        updates.append((f"test.{name}.enabled", value))
    PropertiesFile(path).set_many(updates)


def set_test_enabled(path, test_name, enabled):
    if not re.fullmatch(r"[^\s=#]+", test_name or ""):
        raise ValueError(f"invalid test name: {test_name!r}")
    PropertiesFile(path).set(f"test.{test_name}.enabled", "true" if enabled else "false")


def set_group_enabled(path, group_name, enabled):
    if not re.fullmatch(r"[A-Za-z0-9_-]+", group_name or ""):
        raise ValueError(f"invalid group name: {group_name!r}")
    PropertiesFile(path).set(f"group.{group_name}.enabled", "true" if enabled else "false")


def set_run_only(path, value):
    # Comma-separated class names / package wildcards only — see TestSelection.matchesClass.
    if not re.fullmatch(r"[A-Za-z0-9_.,*\s]*", value or ""):
        raise ValueError("run.only may only contain letters, digits, '.', '_', '*', ',' and spaces")
    cleaned = ",".join(p.strip() for p in (value or "").split(",") if p.strip())
    PropertiesFile(path).set("run.only", cleaned)
