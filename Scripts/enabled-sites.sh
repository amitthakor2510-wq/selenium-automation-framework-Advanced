#!/usr/bin/env bash
# Usage:
#   Scripts/enabled-sites.sh                  # newline list of every enabled site
#   Scripts/enabled-sites.sh --browser-only    # same, minus "mobile"
#   Scripts/enabled-sites.sh --json            # ["demoqa","saucedemo"] (empty -> [])
#   Scripts/enabled-sites.sh --browser-only --json
#   Scripts/enabled-sites.sh --check <site>    # exit 0 if enabled, 1 if disabled/unknown
#   Scripts/enabled-sites.sh --dotenv          # SITE_<NAME>_ENABLED=true|false, one per line
#
# The single, shared reader for pipeline-config.properties (repo root) —
# GitHub Actions' matrix-setup job, the Jenkinsfile's "Discover Site
# Projects" stage, and GitLab CI's generate-pipeline-config job all call
# THIS script instead of each parsing the config file their own way, so
# there is exactly one place that knows the file's format. Deliberately
# dependency-free (grep/sed/paste only — no python, no yq, no jq) so it
# runs unmodified on GitHub's ubuntu/macos runners, a bare-metal Jenkins
# host, and GitLab's shared or Docker runners alike.
set -euo pipefail
cd "$(dirname "$0")/.."

CONFIG_FILE="pipeline-config.properties"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "ERROR: $CONFIG_FILE not found at repo root. This is the master" >&2
    echo "site on/off switch — see its header comment. Nothing can safely" >&2
    echo "run without it, so this fails closed rather than assuming a default." >&2
    exit 2
fi

# Every "site.<name>.enabled=true" line (no inline comments, no spaces
# around "=" — matches the file's own documented format) contributes
# <name> to the list. A site with enabled=false, or with no line at all,
# is simply absent from this list — that's the fail-safe: unknown/missing
# sites are disabled by default, never silently on.
all_enabled() {
    grep -E '^site\.[a-zA-Z0-9_-]+\.enabled=true[[:space:]]*$' "$CONFIG_FILE" \
        | sed -E 's/^site\.([a-zA-Z0-9_-]+)\.enabled=true[[:space:]]*$/\1/'
}

all_known() {
    grep -E '^site\.[a-zA-Z0-9_-]+\.enabled=(true|false)[[:space:]]*$' "$CONFIG_FILE" \
        | sed -E 's/^site\.([a-zA-Z0-9_-]+)\.enabled=(true|false)[[:space:]]*$/\1/'
}

to_json() {
    local list="$1"
    if [ -z "$list" ]; then
        echo "[]"
        return
    fi
    local csv
    csv=$(echo "$list" | paste -sd, -)
    echo "[\"$(echo "$csv" | sed 's/,/","/g')\"]"
}

case "${1:-}" in
    --check)
        site="${2:?usage: enabled-sites.sh --check <site>}"
        grep -qE "^site\.${site}\.enabled=true[[:space:]]*\$" "$CONFIG_FILE"
        ;;
    --json)
        to_json "$(all_enabled)"
        ;;
    --browser-only)
        if [ "${2:-}" = "--json" ]; then
            to_json "$(all_enabled | grep -v '^mobile$' || true)"
        else
            all_enabled | grep -v '^mobile$' || true
        fi
        ;;
    --dotenv)
        while read -r site; do
            [ -z "$site" ] && continue
            upper=$(echo "$site" | tr '[:lower:]-' '[:upper:]_')
            if grep -qE "^site\.${site}\.enabled=true[[:space:]]*\$" "$CONFIG_FILE"; then
                echo "SITE_${upper}_ENABLED=true"
            else
                echo "SITE_${upper}_ENABLED=false"
            fi
        done < <(all_known)
        ;;
    *)
        all_enabled
        ;;
esac
