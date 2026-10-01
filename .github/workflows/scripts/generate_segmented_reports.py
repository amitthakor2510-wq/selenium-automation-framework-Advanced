"""
Builds genuinely separate Allure reports sliced by browser, site/app,
test type (suite), and category (TestNG group) — not just filter chips
inside one combined dashboard. Used locally (`mvn test` writes
target/allure-results, then run this) and from all three CI pipelines
(Jenkinsfile, github-ci.yml, .gitlab-ci.yml) after their own per-site
`mvn test` runs have been merged into one target/allure-results tree.

WHY THIS EXISTS: a single Allure report mixes every site/browser/suite/
category's tests together with only internal tabs to tell them apart —
easy to open the wrong test and not notice. Allure has no built-in way to
emit N separate report.html outputs from one results directory, so this
script does it by COPYING each result (+ its attachments + shared
containers/environment/categories) into a per-dimension results folder,
then invoking `allure generate` once per folder.

Reads:  <results-dir>/*-result.json          (default target/allure-results)
Writes: <output-dir>/<dimension>/<slug>/report/index.html   (one per value)
        <output-dir>/segments.json                          (manifest)

Labels read from each result.json (see TestListener.beforeInvocation):
  - site      -> "By Site / App"       (label "site")
  - browser   -> "By Browser"          (label "browser", or "platform" for mobile)
  - testType  -> "By Test Type"        (label "parentSuite", emitted automatically
                                         by allure-testng from the TestNG <suite> name)
  - category  -> "By Category"         (label "tag", one per TestNG group — a
                                         test with 2 groups appears in 2 category
                                         segments, which is correct: it belongs to both)
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys


def slugify(value):
    slug = re.sub(r"[^a-z0-9]+", "-", str(value).lower()).strip("-")
    return slug or "unknown"


def find_allure_binary(explicit):
    if explicit:
        return explicit
    # Vendored CLI (.allure/allure-<version>/bin/allure[.bat]) — works even
    # when no `allure` command is on PATH (e.g. a fresh CI runner, or a
    # dev machine that never installed the Allure Commandline tool).
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    vendor_dir = os.path.join(root, ".allure")
    if os.path.isdir(vendor_dir):
        for name in sorted(os.listdir(vendor_dir), reverse=True):
            bin_name = "allure.bat" if os.name == "nt" else "allure"
            candidate = os.path.join(vendor_dir, name, "bin", bin_name)
            if os.path.isfile(candidate):
                return candidate
    return "allure"  # fall back to whatever's on PATH


def load_results(results_dir):
    """Returns a list of (result_json_path, parsed_dict) for every *-result.json
    found anywhere under results_dir (handles both a flat directory and the
    per-site subfolders some CI jobs still download results into)."""
    results = []
    for dirpath, _dirs, files in os.walk(results_dir):
        for fname in files:
            if fname.endswith("-result.json"):
                path = os.path.join(dirpath, fname)
                try:
                    with open(path, encoding="utf-8") as f:
                        results.append((path, json.load(f)))
                except (json.JSONDecodeError, OSError) as e:
                    print(f"  Skipping unreadable result {path}: {e}", file=sys.stderr)
    return results


def label_value(result, name):
    for label in result.get("labels", []):
        if label.get("name") == name:
            return label.get("value")
    return None


def dimensions_for(result):
    """Returns a dict of dimension -> [values] for one result. Most dimensions
    have exactly one value; "category" can have several (one per TestNG group)."""
    site = label_value(result, "site") or "unknown-site"
    browser = label_value(result, "browser") or label_value(result, "platform") or "unknown-browser"
    test_type = label_value(result, "parentSuite") or label_value(result, "suite") or "unknown-suite"
    categories = [l["value"] for l in result.get("labels", []) if l.get("name") == "tag"]
    if not categories:
        categories = ["uncategorized"]
    severity = label_value(result, "severity") or "normal"
    dims = {
        "site": [site],
        "browser": [browser],
        "testType": [test_type],
        "category": categories,
        "severity": [severity],
    }
    # Only tests that failed at least once before eventually passing carry
    # this label (see TestListener's afterTestInvocation SUCCESS branch) —
    # most runs have none, so this dimension is entirely absent from
    # segments.json/the landing page rather than showing an empty "Flaky"
    # section every time.
    if label_value(result, "flaky") == "true":
        dims["flaky"] = ["flaky"]
    return dims


def copy_shared_files(results_dir, dest_dir):
    """environment.properties/categories.json/executor.json apply to the whole
    run, and Allure containers (*-container.json, before/after hooks) are cheap
    to duplicate everywhere — Allure only actually uses the ones whose uuid a
    copied result references, so over-copying is harmless."""
    os.makedirs(dest_dir, exist_ok=True)
    for fname in ("environment.properties", "categories.json", "executor.json"):
        src = os.path.join(results_dir, fname)
        if os.path.isfile(src):
            shutil.copy2(src, dest_dir)
    for dirpath, _dirs, files in os.walk(results_dir):
        for fname in files:
            if fname.endswith("-container.json"):
                shutil.copy2(os.path.join(dirpath, fname), dest_dir)


def copy_result_and_attachments(result_json_path, result, dest_dir):
    os.makedirs(dest_dir, exist_ok=True)
    shutil.copy2(result_json_path, dest_dir)
    src_dir = os.path.dirname(result_json_path)
    for attachment in result.get("attachments", []):
        source = attachment.get("source")
        if not source:
            continue
        src = os.path.join(src_dir, source)
        if os.path.isfile(src):
            shutil.copy2(src, dest_dir)
        else:
            print(f"  Warning: attachment {source} referenced by "
                  f"{os.path.basename(result_json_path)} was not found on disk "
                  f"(report will show a broken link for it).", file=sys.stderr)


def generate_report(allure_bin, results_dir, report_dir):
    os.makedirs(report_dir, exist_ok=True)
    cmd = [allure_bin, "generate", results_dir, "-o", report_dir, "--clean"]
    try:
        subprocess.run(cmd, check=True, capture_output=True, text=True)
        return True
    except FileNotFoundError:
        print(f"  Could not find/run allure binary '{allure_bin}'. Pass --allure-bin, "
              f"or install the Allure Commandline tool.", file=sys.stderr)
        return False
    except subprocess.CalledProcessError as e:
        print(f"  allure generate failed for {results_dir}: {e.stderr}", file=sys.stderr)
        return False


DIMENSION_LABELS = {
    "site": "By Site / App",
    "browser": "By Browser",
    "testType": "By Test Type",
    "category": "By Category",
    "severity": "By Severity",
    "flaky": "Flaky (passed only after a retry)",
}

DIMENSION_ORDER = ["browser", "site", "testType", "category", "severity", "flaky"]


def find_extent_reports(extent_dir):
    """Recursively finds every nested index.html under target/extent-reports/
    (ExtentManager.create() writes <site>/<browser-or-mobile>/<suite>/index.html)
    and returns [(label, path)], sorted. Mirrors the same recursive-walk fix
    applied to the CI landing-page scripts — a flat glob here would silently
    find none of them."""
    if not os.path.isdir(extent_dir):
        return []
    reports = []
    for dirpath, _dirs, files in os.walk(extent_dir):
        if "index.html" in files:
            rel = os.path.relpath(os.path.join(dirpath, "index.html"), extent_dir)
            label = os.path.dirname(rel).replace(os.sep, " — ") or "report"
            reports.append((label, os.path.join(dirpath, "index.html")))
    return sorted(reports)


def build_landing_page(landing_page_path, combined_output, extent_dir, manifest):
    """Writes a single self-contained target/report-index.html linking every
    report this run produced — combined Allure, every segmented Allure
    report grouped by dimension, and every nested Extent report — so a local
    dev (or anyone browsing a downloaded/archived artifact tree) has one
    obvious starting point instead of hunting through target/ by hand. All
    links are relative to this file's own directory so the page still works
    after the whole target/ tree is copied/zipped elsewhere."""
    landing_dir = os.path.dirname(os.path.abspath(landing_page_path)) or "."

    def rel(path):
        return os.path.relpath(os.path.abspath(path), landing_dir).replace(os.sep, "/")

    sections = []

    if os.path.isfile(os.path.join(combined_output, "index.html")):
        sections.append(
            '<section><h2>Allure — All Results (Combined)</h2><ul>'
            f'<li><a href="{html_escape(rel(os.path.join(combined_output, "index.html")))}">Open</a></li>'
            '</ul></section>'
        )

    by_dimension = {}
    for seg in manifest:
        if seg.get("generated"):
            by_dimension.setdefault(seg["dimension"], []).append(seg)
    for dimension in DIMENSION_ORDER:
        segs = sorted(by_dimension.get(dimension, []), key=lambda s: s["value"])
        if not segs:
            continue
        items = "".join(
            f'<li><a href="{html_escape(rel(seg["reportPath"]))}">{html_escape(str(seg["value"]))}</a> '
            f'<small>({seg["count"]} test{"s" if seg["count"] != 1 else ""})</small></li>'
            for seg in segs
        )
        sections.append(f'<section><h2>Allure — {html_escape(DIMENSION_LABELS[dimension])}</h2><ul>{items}</ul></section>')

    extent_reports = find_extent_reports(extent_dir)
    if extent_reports:
        items = "".join(
            f'<li><a href="{html_escape(rel(path))}">{html_escape(label)}</a></li>'
            for label, path in extent_reports
        )
        sections.append(f'<section><h2>Extent Reports</h2><ul>{items}</ul></section>')

    if not sections:
        sections.append("<p>No reports found — run <code>mvn test</code> first, then this script.</p>")

    html_doc = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Test Reports</title>
<style>
  body {{ font-family: -apple-system, Segoe UI, Roboto, Arial, sans-serif; max-width: 900px; margin: 2rem auto; padding: 0 1rem; color: #1E293B; }}
  h1 {{ margin-bottom: 0.25rem; }}
  .subtitle {{ color: #64748B; margin-top: 0; }}
  section {{ margin: 1.5rem 0; padding: 1rem; border: 1px solid #E2E8F0; border-radius: 8px; }}
  h2 {{ margin-top: 0; font-size: 1.05rem; color: #334155; }}
  ul {{ margin: 0.5rem 0 0; padding-left: 1.25rem; }}
  li {{ margin: 0.25rem 0; }}
  a {{ color: #6D28D9; text-decoration: none; }}
  a:hover {{ text-decoration: underline; }}
  small {{ color: #94A3B8; }}
</style>
</head>
<body>
<h1>Test Reports</h1>
<p class="subtitle">Generated by Scripts/generate_segmented_reports.py</p>
{''.join(sections)}
</body>
</html>
"""
    os.makedirs(landing_dir, exist_ok=True)
    with open(landing_page_path, "w", encoding="utf-8") as f:
        f.write(html_doc)


def html_escape(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
             .replace('"', "&quot;"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", default="target/allure-results")
    parser.add_argument("--output-dir", default="target/allure-segmented")
    parser.add_argument("--allure-bin", default=None)
    parser.add_argument("--skip-combined", action="store_true",
                         help="Don't also (re)generate a combined 'all results' report — "
                              "CI pipelines usually already build that separately.")
    parser.add_argument("--combined-output", default="target/allure-report")
    parser.add_argument("--extent-dir", default="target/extent-reports",
                         help="Where ExtentManager wrote its nested reports — only used to "
                              "list them on the landing page, nothing here reads/copies them.")
    parser.add_argument("--landing-page", default="target/report-index.html",
                         help="Where to write the single linking-everything-together HTML page. "
                              "Pass an empty string to skip it.")
    args = parser.parse_args()

    if not os.path.isdir(args.results_dir):
        print(f"No results directory at {args.results_dir} — nothing to segment.", file=sys.stderr)
        sys.exit(0)  # not fatal: a site/browser combo with zero tests this run is normal

    allure_bin = find_allure_binary(args.allure_bin)
    results = load_results(args.results_dir)
    if not results:
        print(f"No *-result.json files found under {args.results_dir}.", file=sys.stderr)
        sys.exit(0)

    print(f"Found {len(results)} test result(s). Using allure binary: {allure_bin}")

    # segment_key -> {"dimension":..., "value":..., "results_dir":..., "count": int}
    segments = {}
    for result_json_path, result in results:
        dims = dimensions_for(result)
        for dimension, values in dims.items():
            for value in values:
                key = (dimension, value)
                if key not in segments:
                    slug = slugify(value)
                    seg_results_dir = os.path.join(args.output_dir, dimension, slug, "results")
                    segments[key] = {
                        "dimension": dimension,
                        "dimensionLabel": DIMENSION_LABELS[dimension],
                        "value": value,
                        "slug": slug,
                        "resultsDir": seg_results_dir,
                        "reportDir": os.path.join(args.output_dir, dimension, slug, "report"),
                        "count": 0,
                    }
                seg = segments[key]
                copy_result_and_attachments(result_json_path, result, seg["resultsDir"])
                seg["count"] += 1

    for seg in segments.values():
        copy_shared_files(args.results_dir, seg["resultsDir"])

    manifest = []
    for seg in segments.values():
        ok = generate_report(allure_bin, seg["resultsDir"], seg["reportDir"])
        print(f"  [{seg['dimensionLabel']}] {seg['value']}: {seg['count']} test(s) "
              f"-> {seg['reportDir']}/index.html {'OK' if ok else 'FAILED'}")
        manifest.append({
            "dimension": seg["dimension"],
            "dimensionLabel": seg["dimensionLabel"],
            "value": seg["value"],
            "slug": seg["slug"],
            "count": seg["count"],
            "reportPath": os.path.join(args.output_dir, seg["dimension"], seg["slug"], "report", "index.html"),
            "generated": ok,
        })

    if not args.skip_combined:
        ok = generate_report(allure_bin, args.results_dir, args.combined_output)
        print(f"  [Combined] All results: {len(results)} test(s) "
              f"-> {args.combined_output}/index.html {'OK' if ok else 'FAILED'}")

    manifest_path = os.path.join(args.output_dir, "segments.json")
    os.makedirs(args.output_dir, exist_ok=True)
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
    print(f"Wrote manifest: {manifest_path}")

    if args.landing_page:
        build_landing_page(args.landing_page, args.combined_output, args.extent_dir, manifest)
        print(f"Wrote landing page: {args.landing_page}")


if __name__ == "__main__":
    main()
