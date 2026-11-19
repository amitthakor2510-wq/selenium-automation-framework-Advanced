#!/usr/bin/env bash
# Scripts/make-release-zip.sh — build a clean source archive of this project.
#
# Why: zipping the project folder by hand also packs everything git ignores —
# .env (DASHBOARD_PASSWORD), .git/, .idea/, __pycache__/, dashboard run data,
# generated reports, self-healing-data/. This script archives only what git
# would track: tracked files plus new, not-ignored files, so uncommitted work
# is included but secrets and runtime artifacts never are.
#
# Usage:  Scripts/make-release-zip.sh [output.zip]
# Default output: ../<project-folder>-release-<YYYYmmdd-HHMM>.zip
set -euo pipefail

cd "$(dirname "$0")/.."
command -v git >/dev/null || { echo "git is required" >&2; exit 1; }
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
  echo "Not a git work tree — cannot tell tracked from ignored files." >&2
  echo "Clone the repository (or run 'git init' and review .gitignore) first." >&2
  exit 1
}

name="$(basename "$PWD" | tr ' ' '-')"
out="${1:-../${name}-release-$(date +%Y%m%d-%H%M).zip}"
case "$out" in /*) ;; *) out="$PWD/$out" ;; esac

# Belt and braces: even if someone force-added one of these, never ship it.
deny='(^|/)(\.git|\.idea|__pycache__|self-healing-data|target)(/|$)|^Scripts/dashboard/(data/runs|auth/(users|secrets\.enc)\.json)'

list="$(mktemp)"; trap 'rm -f "$list"' EXIT
# `--cached` also lists tracked files that were deleted locally, so keep real files only.
# Any .env / .env.* is secret-bearing except the template files, which hold no secrets.
git ls-files -z --cached --others --exclude-standard | tr '\0' '\n' | grep -Ev "$deny" \
  | while IFS= read -r f; do
      [ -f "$f" ] || continue
      case "$(basename "$f")" in
        .env.example|.env.sample|.env.template) ;;
        .env|.env.*) continue ;;
      esac
      printf '%s\n' "$f"
    done > "$list" || true
[ -s "$list" ] || { echo "Nothing to archive." >&2; exit 1; }

rm -f "$out"
if command -v zip >/dev/null; then
  zip -q "$out" -@ < "$list"
else
  python3 - "$out" "$list" <<'PY'
import sys, zipfile, os
out, lst = sys.argv[1:3]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z, open(lst) as f:
    for line in f:
        p = line.rstrip("\n")
        if os.path.isfile(p):
            z.write(p)
PY
fi

echo "Wrote $out ($(wc -l < "$list") files, $(du -h "$out" | cut -f1))"
echo "Excluded by design: .env, .git/, .idea/, __pycache__/, target/, self-healing-data/, dashboard run data."
