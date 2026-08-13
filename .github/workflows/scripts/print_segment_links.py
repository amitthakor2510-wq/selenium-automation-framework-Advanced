"""
Prints one markdown table row per generated segmented Allure report (see
Scripts/generate_segmented_reports.py), for the "Publish report links to
job summary" step in github-ci.yml. Kept as its own small script — rather
than an inline heredoc in the workflow YAML — for the same reason every
other step in that job already delegates to a script here: a YAML block
scalar (`run: |`) requires every line to stay at or above its established
indentation, which a flush-left embedded Python script violates.

Reads: <segments-dir>/segments.json (default target/gh-pages-publish/allure-segmented)
Env:   BASE_URL (required) — the published site's base URL for this run
"""
import argparse
import json
import os
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--segments-dir", default="target/gh-pages-publish/allure-segmented")
    args = parser.parse_args()

    base_url = os.environ.get("BASE_URL", "").rstrip("/")
    if not base_url:
        print("BASE_URL is not set — skipping segmented report links.", file=sys.stderr)
        return

    manifest_path = os.path.join(args.segments_dir, "segments.json")
    if not os.path.isfile(manifest_path):
        return

    with open(manifest_path, encoding="utf-8") as f:
        segments = json.load(f)

    for seg in segments:
        if not seg.get("generated"):
            continue
        label = f"Allure — {seg['dimensionLabel']}: {seg['value']}"
        path = f"allure-segmented/{seg['dimension']}/{seg['slug']}/report/index.html"
        url = f"{base_url}/{path}"
        print(f"| {label} | [{url}]({url}) |")


if __name__ == "__main__":
    main()
