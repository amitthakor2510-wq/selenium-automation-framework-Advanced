#!/usr/bin/env bash
# One command to start the Automation Console with the runner switched ON, so everything else
# (every suite, API run, report, crawler, TIA ...) is done from the browser with switches.
#
#   Scripts/start-dashboard.sh                 # http://localhost:5057/control
#   Scripts/start-dashboard.sh --port 8080
#   DASHBOARD_RUNNER_ALLOW_SYSTEM=1 Scripts/start-dashboard.sh   # also enable Docker grid + scaffolding buttons
#
# DASHBOARD_PASSWORD is read from the environment or from .env (DASHBOARD_* lines only, parsed as
# plain KEY=VALUE text — .env is never sourced/executed). With no password set, the dashboard
# generates a random one and prints it. Binds to localhost unless you pass --host / DASHBOARD_HOST.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in
      DASHBOARD_[A-Z_]*=*)
        key="${line%%=*}"; val="${line#*=}"
        val="${val%\"}"; val="${val#\"}"; val="${val%\'}"; val="${val#\'}"
        [ -z "${!key:-}" ] && export "$key=$val"
        ;;
    esac
  done < .env
fi

export DASHBOARD_RUNNER_ENABLED="${DASHBOARD_RUNNER_ENABLED:-1}"
PY="$(command -v python3 || command -v python || true)"
[ -n "$PY" ] || { echo "python3 is required to run the dashboard" >&2; exit 1; }
exec "$PY" Scripts/dashboard/dashboard_server.py "$@"
