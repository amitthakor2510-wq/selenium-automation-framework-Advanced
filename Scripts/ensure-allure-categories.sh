#!/usr/bin/env bash
# Usage:
#   Scripts/ensure-allure-categories.sh [results-dir ...]     (default: target/allure-results)
#
# Why this exists: Allure's "Categories" tab sorts failures by type (product defect,
# element not found, timeout, ...). It reads rules from a categories.json that sits
# directly in the allure-results folder. AllureEnvironmentWriter writes that file when
# a test run starts, but some results never get one next to them: results from a
# run that didn't use BaseTest/TestListener, results merged from several CI jobs, or a
# folder you copied around by hand. Without the file Allure shows
# "Can't find information about categories".
#
# This script copies the one canonical rule file
# (src/main/resources/categories.json) into each results folder that lacks
# categories.json. It also handles folders that hold per-site/per-browser result
# subfolders (the layout Jenkins and GitLab use). Existing categories.json files are
# never overwritten. Always exits 0 so it can't break a report stage.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE="${CATEGORIES_SOURCE:-$SCRIPT_DIR/../src/main/resources/categories.json}"

if [[ ! -f "$SOURCE" ]]; then
  echo "ensure-allure-categories: canonical file not found: $SOURCE (nothing done)" >&2
  exit 0
fi

has_results() {
  # true if the folder directly contains Allure result files
  compgen -G "$1/*-result.json" > /dev/null
}

ensure_one() {
  local dir="$1"
  if [[ -f "$dir/categories.json" ]]; then
    echo "ensure-allure-categories: already present: $dir/categories.json"
  else
    cp "$SOURCE" "$dir/categories.json" && echo "ensure-allure-categories: added $dir/categories.json"
  fi
}

[[ $# -eq 0 ]] && set -- target/allure-results

for root in "$@"; do
  if [[ ! -d "$root" ]]; then
    echo "ensure-allure-categories: skipping (not a directory): $root"
    continue
  fi
  has_results "$root" && ensure_one "$root"
  for sub in "$root"/*/; do
    [[ -d "$sub" ]] || continue
    sub="${sub%/}"
    has_results "$sub" && ensure_one "$sub"
  done
done
exit 0
