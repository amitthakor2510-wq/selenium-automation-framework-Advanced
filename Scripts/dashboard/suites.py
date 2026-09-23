"""
Maps each testng-suites/*.xml file to the site it belongs to, so the
dashboard can suggest the exact `-DsuiteXmlFile=...` to pair with
`-Dsite=...` — the two flags pom.xml documents as a matched pair (see
docs/configuration.md and the "how can I disable saucedemo and demoqa"
/ "it still runs demoqa" conversation this dashboard grew out of:
passing `-Dsite` without `-DsuiteXmlFile` runs the wrong suite against
the right site).

Deliberately does NOT hardcode a site -> filename-prefix table (that
goes stale the moment a suite file is renamed or a new one added).
Instead it scans each suite file's actual `com.automation(.mobile)?
.sites.<name>` package references — the file's own contents are already
the ground truth for which site it tests, so this can't drift out of
sync with testng-suites/ the way a hardcoded mapping could. A suite
file that references more than one site, or none, is reported as-is
rather than guessed at.
"""
import glob
import os
import re

_SITE_PKG_RE = re.compile(r"com\.automation\.(?:mobile\.)?sites\.([a-z0-9_]+)")
_IGNORED_SEGMENTS = {"listeners", "core"}


def _sites_referenced(xml_text):
    found = set()
    for m in _SITE_PKG_RE.finditer(xml_text):
        segment = m.group(1)
        if segment not in _IGNORED_SEGMENTS:
            found.add(segment)
    if "com.automation.mobile." in xml_text:
        found.add("mobile")
    return found


def list_suites(suites_dir, known_site_names):
    """
    {"bySite": {"<site-name-as-cased-in-pipeline-config>": ["a.xml", "b.xml", ...]}, "unmapped": ["c.xml", ...]}
    known_site_names preserves the exact case pipeline-config.properties
    uses (e.g. "SAHMAT", not "sahmat") since that's what -Dsite= expects.
    """
    lower_to_actual = {name.lower(): name for name in known_site_names}
    by_site = {name: [] for name in known_site_names}
    unmapped = []

    for path in sorted(glob.glob(os.path.join(suites_dir, "*.xml"))):
        filename = os.path.basename(path)
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except OSError:
            continue
        referenced = _sites_referenced(text)
        matched = [lower_to_actual[r] for r in referenced if r in lower_to_actual]
        if len(matched) == 1:
            by_site[matched[0]].append(filename)
        else:
            # 0 matches (couldn't determine) or >1 (a suite spanning
            # several sites) — surfaced rather than guessed at.
            unmapped.append(filename)

    for files in by_site.values():
        files.sort()
    return {"bySite": by_site, "unmapped": unmapped}
