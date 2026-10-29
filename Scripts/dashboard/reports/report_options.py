"""
Maps the wizard's report choices ("Allure / Extent / summary / PDF") to the framework's
real properties, and says which choices exist for which test type.

Only keys that exist in src/test/resources/config/global.properties are ever emitted.
"""

# which report choices make sense per test type
AVAILABLE = {
    "ui": ["allure", "extent", "summary", "pdf"],
    "accessibility": ["allure", "extent", "summary", "pdf"],
    "api": ["allure", "summary"],
    "openapi": ["allure", "summary"],
    "mobile": ["summary"],
    "performance": ["summary"],
    "crawler": ["summary"],
}

LABELS = {
    "allure": "Allure report (history, categories)",
    "extent": "Extent HTML report",
    "summary": "Run summary page (pass/fail, link to the type's own report)",
    "pdf": "PDF report",
}

DEFAULTS = {"allure": True, "extent": True, "summary": True, "pdf": False}


def available(test_type):
    return list(AVAILABLE.get(test_type, []))


def normalize(test_type, chosen):
    """Drop choices that don't apply to the type; fill defaults for the ones that do."""
    chosen = chosen or {}
    return {k: bool(chosen.get(k, DEFAULTS[k])) for k in AVAILABLE.get(test_type, [])}


def to_properties(test_type, chosen):
    """-D properties for the framework (only what a choice actually switches)."""
    props = {}
    norm = normalize(test_type, chosen)
    if "pdf" in norm:
        props["report.pdf.enabled"] = "true" if norm["pdf"] else "false"
    return props


def artifact_dirs(test_type, chosen):
    """Repo-relative directories to snapshot into the run folder after the run."""
    norm = normalize(test_type, chosen)
    dirs = []
    if norm.get("allure"):
        dirs.append("target/allure-results")
    if norm.get("extent"):
        dirs.append("target/extent-reports")
    if norm.get("pdf"):
        dirs.append("target/pdf-reports")
    primary = {"api": "target/api-report", "openapi": "target/api-report", "performance": "target/perf-reports",
               "crawler": "target/crawler", "mobile": "target/mobile-crawler"}.get(test_type)
    if primary and norm.get("summary"):
        dirs.append(primary)
    return dirs
