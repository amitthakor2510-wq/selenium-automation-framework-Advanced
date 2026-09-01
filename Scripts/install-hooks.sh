#!/usr/bin/env bash
# Usage: Scripts/install-hooks.sh
#
# One-time setup (per clone) that points git at the version-controlled
# .githooks/ directory instead of the default, non-version-controlled
# .git/hooks/ — so the pre-commit checks (Checkstyle, gitleaks — see
# .githooks/pre-commit) ship with the repo and stay current on every
# `git pull`, with no re-run of this script needed after the first time.
#
# Uninstall (go back to git's own default .git/hooks/, no hooks run):
#   git config --unset core.hooksPath
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

if [ ! -d ".git" ]; then
  echo "ERROR: run this from inside a git checkout of the repo (no .git/ found here)." >&2
  exit 1
fi

chmod +x .githooks/pre-commit
git config core.hooksPath .githooks

echo "[install-hooks] Done — core.hooksPath now points at .githooks/."
echo "[install-hooks] Pre-commit will run gitleaks (if installed) and Checkstyle"
echo "[install-hooks] (only when staged .java files change) on every commit."
echo "[install-hooks] Bypass a single commit with: SKIP_HOOKS=1 git commit ..."
