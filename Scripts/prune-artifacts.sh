#!/usr/bin/env bash
# Usage: ./Scripts/prune-artifacts.sh [--dir <path>] [--keep-runs <N>] [--gap-minutes <N>]
#                                      [--extension <.ext>] [--dry-run]
# Examples:
#   ./Scripts/prune-artifacts.sh                          # keep last 5 runs of target/videos
#   ./Scripts/prune-artifacts.sh --dry-run                 # show what would be deleted, delete nothing
#   ./Scripts/prune-artifacts.sh --keep-runs 10            # keep more history
#   ./Scripts/prune-artifacts.sh --dir target/videos --gap-minutes 15
#
# Thin wrapper around com.automation.core.retention.ArtifactRetentionCleaner (see its own
# javadoc and the prune-artifacts profile in pom.xml). Exists mainly so pruning old local
# recordings doesn't require remembering Maven profile/property syntax:
#
#   mvn -q exec:java@prune-artifacts -Pprune-artifacts -Dprune.keepRuns=10 -Dprune.dryRun=true
#
# VideoRecorder writes every recording flat into target/videos/ (see its own javadoc) with no
# per-run subfolder, so nothing on disk ever tells you where one local `mvn test` invocation
# ended and the next began. ArtifactRetentionCleaner reconstructs "runs" from file timestamps
# instead — see its own javadoc for exactly how, and why that's a safe heuristic here.
#
# This only ever deletes files under --dir; it never touches CI, git, or gh-pages/GitLab Pages.
# For CI-side retention (uploaded artifact expiry, gh-pages branch history, GitLab Pages job
# artifact expiry), see docs/RETENTION_POLICY.md.

set -euo pipefail
cd "$(dirname "$0")/.."

DIR="target/videos"
KEEP_RUNS="5"
GAP_MINUTES="30"
EXTENSION=".avi"
DRY_RUN="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --keep-runs) KEEP_RUNS="$2"; shift 2 ;;
    --gap-minutes) GAP_MINUTES="$2"; shift 2 ;;
    --extension) EXTENSION="$2"; shift 2 ;;
    --dry-run) DRY_RUN="true"; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

mvn -q exec:java@prune-artifacts -Pprune-artifacts \
  -Dprune.dir="$DIR" \
  -Dprune.keepRuns="$KEEP_RUNS" \
  -Dprune.gapMinutes="$GAP_MINUTES" \
  -Dprune.extension="$EXTENSION" \
  -Dprune.dryRun="$DRY_RUN"
