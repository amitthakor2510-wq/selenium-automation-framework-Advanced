"""
Named, curated site-level combinations (Scripts/dashboard/presets.json) —
e.g. "demoqa only", "API only" — applied in one click instead of toggling
each site switch by hand.

Deliberately SITE-level only, never test-level. A site is a simple
independent boolean (see config_files.set_site_enabled), so a preset
combining several is unambiguous. A TestNG test can carry several groups
at once (e.g. {"smoke", "regression"}), and TestSelection disables a
test if ANY of its groups is disabled — so a "smoke only" TEST preset
would silently also disable every smoke test that happens to also carry
"regression", which is not what "smoke only" would visibly promise. That
ambiguity is why test-level bulk changes are instead exposed as the
"enable/disable all tests in this section" actions (see
dashboard_server.py) — deterministic because they name the exact class
list you already see on screen, not a semantic label to interpret.

presets.json is a plain data file — edit it (or add entries) without
touching this module or dashboard_server.py.
"""
import json
import os

_THIS_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_PATH = os.path.join(_THIS_DIR, "presets.json")


def load(path=DEFAULT_PATH):
    """List of preset dicts, or [] (with the reason left for the caller to
    log) if the file is missing/invalid — a bad presets.json should never
    take down the rest of the dashboard."""
    if not os.path.exists(path):
        return []
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (json.JSONDecodeError, OSError):
        return []
    if not isinstance(data, list):
        return []
    return [p for p in data if isinstance(p, dict) and "id" in p and "sites" in p]


def find(preset_id, path=DEFAULT_PATH):
    for preset in load(path):
        if preset["id"] == preset_id:
            return preset
    return None


def apply(pipeline_config_path, preset, known_site_names):
    """
    Writes every site in the preset that's actually a known site (one
    file write via set_many — see PropertiesFile.set_many). Returns the
    list of preset site names that were skipped because they aren't in
    pipeline-config.properties (e.g. the preset predates a site being
    renamed/removed) — surfaced to the UI rather than silently ignored
    or, worse, silently creating a stray new site line.
    """
    from config_files import PropertiesFile  # local import: avoids a cycle at module load time

    known = set(known_site_names)
    updates, skipped = [], []
    for name, enabled in preset["sites"].items():
        if name in known:
            updates.append((f"site.{name}.enabled", "true" if enabled else "false"))
        else:
            skipped.append(name)
    if updates:
        PropertiesFile(pipeline_config_path).set_many(updates)
    return skipped
