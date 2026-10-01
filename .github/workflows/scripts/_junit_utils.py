"""
Shared helper for reading TestNG's JUnitReportReporter output
(target/surefire-reports/TEST-<FullyQualifiedClassName>.xml), which is the
one surefire artifact whose schema is simple and per-class, unlike
testng-results.xml (a different, richer schema) or TEST-TestSuite.xml (an
aggregate that would double-count every testcase already reported in the
per-class files). Used by both compute_flaky_trend.py (needs a
test -> pass/fail/skip map to build history) and post_pr_comment.py (needs
just the totals) so the parsing logic — and its edge cases — lives in one
place instead of two.
"""
import glob
import os
import xml.etree.ElementTree as ET

# Files that exist alongside the real per-class TEST-*.xml files but would
# either double-count (TEST-TestSuite.xml, an aggregate of everything else
# in the directory) or aren't the JUnit schema this parser expects
# (testng-results.xml, TestNG's own native report format).
_SKIP_FILENAMES = {"TEST-TestSuite.xml", "testng-results.xml"}


def parse_surefire_dir(surefire_dir):
    """
    Walk every TEST-*.xml under surefire_dir and return:
      - results: dict of "fully.qualified.ClassName.methodName" -> "pass" | "fail" | "skip"
      - totals: dict with total/passed/failed/skipped counts

    Silently skips files that don't parse as valid XML or don't match the
    expected <testsuite><testcase/></testsuite> shape, since a partial or
    corrupted report from a crashed test run shouldn't take down reporting
    for every other test class that ran fine.
    """
    results = {}
    if not os.path.isdir(surefire_dir):
        return results, {"total": 0, "passed": 0, "failed": 0, "skipped": 0}

    for path in sorted(glob.glob(os.path.join(surefire_dir, "**", "TEST-*.xml"), recursive=True)):
        if os.path.basename(path) in _SKIP_FILENAMES:
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        if root.tag != "testsuite":
            continue
        suite_name = root.get("name", os.path.basename(path))
        for tc in root.findall("testcase"):
            name = tc.get("name", "unknown")
            classname = tc.get("classname", suite_name)
            key = f"{classname}.{name}"
            if tc.find("skipped") is not None:
                status = "skip"
            elif tc.find("failure") is not None or tc.find("error") is not None:
                status = "fail"
            else:
                status = "pass"
            # If the same test key shows up in more than one uploaded
            # artifact (shouldn't normally happen — class names are
            # namespaced per site — but retries/reruns could in principle
            # produce duplicates), prefer "fail" over "pass"/"skip" so a
            # single failure isn't silently hidden by a later duplicate.
            if key in results and results[key] == "fail":
                continue
            results[key] = status

    total = len(results)
    failed = sum(1 for v in results.values() if v == "fail")
    skipped = sum(1 for v in results.values() if v == "skip")
    passed = total - failed - skipped
    return results, {"total": total, "passed": passed, "failed": failed, "skipped": skipped}


def pages_base_url(owner, repo):
    """
    Standard GitHub Pages URL shape for a project served from a
    gh-pages branch: https://<owner>.github.io/<repo>. The one
    exception is a user/org Pages repo named exactly "<owner>.github.io",
    which GitHub serves at the bare domain root instead.
    """
    if repo == f"{owner}.github.io":
        return f"https://{owner}.github.io"
    return f"https://{owner}.github.io/{repo}"
