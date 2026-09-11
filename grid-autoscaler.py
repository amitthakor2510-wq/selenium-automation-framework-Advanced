#!/usr/bin/env python3
"""
Scripts/grid-autoscaler.py — polls the Selenium Grid hub's GraphQL
endpoint for queued (pending) sessions per browser, and scales a pool of
extra browser-node containers up/down via `docker compose --scale`, on
top of the fixed single-node-per-browser debug setup already defined in
docker-compose.yml (chrome/firefox/edge — kept exactly as-is, still the
normal noVNC-watchable path for a plain `docker compose up -d`).

WHY A SEPARATE POOL, NOT SCALING THE EXISTING chrome/firefox/edge SERVICES:
`docker compose up --scale <service>=N` requires that service to have no
fixed `container_name` and no fixed host port publish (both would collide
across replicas) — chrome/firefox/edge in docker-compose.yml deliberately
have both, for a stable, individually-noVNC-watchable debug node. Rather
than strip those (breaking the existing debug workflow docs/ci-cd.md and
this file's own top comment describe), docker-compose.autoscale.yml adds
brand-new chrome-pool/firefox-pool/edge-pool services with neither — this
script is the only thing that ever changes their replica count.

USAGE:
  # One-shot: check the grid once, scale if needed, exit.
  python3 Scripts/grid-autoscaler.py --once

  # Daemon: poll every 15s (default) until Ctrl-C.
  python3 Scripts/grid-autoscaler.py

  # See what it WOULD do without touching any containers:
  python3 Scripts/grid-autoscaler.py --once --dry-run

Requires: the grid already up (`docker compose up -d selenium-hub`) and
docker-compose.autoscale.yml layered in — this script always passes both
docker-compose.yml and docker-compose.autoscale.yml (plus
docker-compose.override.yml, if present, so a per-machine port remap like
the existing one for a colliding host port keeps applying) via explicit
-f flags, so it works the same regardless of what's sitting in the
working directory.

KNOWN LIMITATION — scale-down is grid-wide, not per-node: Docker Compose
has no concept of "drain this one replica" for unmanaged Selenium nodes,
so there's no reliable way to know THIS PARTICULAR container has no
active session, only that the browser's queue is empty. To avoid killing
a container mid-test, this script only ever scales ANY pool down when the
grid's TOTAL session count (every browser, not just the one being scaled)
has been zero for the full --scale-down-cooldown window — conservative on
purpose. A real per-node drain would need to correlate a Grid node ID to
its owning container ID (via the node's advertised URI/port), which this
script doesn't attempt — flagged here rather than silently assumed safe.
"""
import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

DEFAULT_HUB_URL = "http://localhost:4444"

# Maps the autoscaled pool's service name (docker-compose.autoscale.yml)
# to the loose (case-insensitive substring) capability-name hints used to
# recognize that browser in a queued session request's capabilities JSON.
# Edge is matched broadly ("edge") since real-world queued requests are
# inconsistent between the W3C-standard "MicrosoftEdge" and "msedge"/
# "edge" depending on client library/version — narrowing to one exact
# string risked silently never matching real Edge requests.
POOL_SERVICES = {
    "chrome": "chrome-pool",
    "firefox": "firefox-pool",
    "edge": "edge-pool",
}
BROWSER_NAME_HINTS = {
    "chrome": ["chrome"],
    "firefox": ["firefox"],
    "edge": ["edge"],
}

# Selenium Grid 4's GraphQL schema — see
# https://www.selenium.dev/documentation/grid/advanced_features/graphql_support/
# grid.sessionCount is the grid-wide active-session total (used for the
# scale-down safety check above); sessionsInfo.sessionQueueRequests is a
# list of JSON-encoded capabilities strings, one per pending request.
GRAPHQL_QUERY = """
{
  grid { sessionCount, maxSession, totalSlots }
  sessionsInfo { sessionQueueRequests }
}
"""


def log(message):
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    print(f"[{ts}] {message}", flush=True)


def fetch_grid_state(hub_url, timeout=10):
    """POSTs GRAPHQL_QUERY to <hub_url>/graphql. Returns the parsed `data`
    object, or None on any network/parse failure — a Grid that's briefly
    unreachable (mid-restart, host under load) should make this poll a
    no-op, not crash the daemon loop."""
    url = hub_url.rstrip("/") + "/graphql"
    payload = json.dumps({"query": GRAPHQL_QUERY}).encode("utf-8")
    req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, json.JSONDecodeError) as e:
        log(f"WARNING: could not reach grid at {url}: {e}")
        return None
    if "errors" in body:
        log(f"WARNING: grid GraphQL returned errors: {body['errors']}")
    return body.get("data")


def count_queued_by_browser(grid_data):
    """Parses sessionsInfo.sessionQueueRequests into a {browser: count}
    dict keyed by POOL_SERVICES' keys. A malformed individual entry is
    skipped, not fatal, so one oddly-shaped queued request doesn't block
    scaling decisions for the rest of the queue."""
    counts = {browser: 0 for browser in POOL_SERVICES}
    if not grid_data:
        return counts
    requests_raw = (grid_data.get("sessionsInfo") or {}).get("sessionQueueRequests") or []
    for raw in requests_raw:
        try:
            caps = json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            continue
        name = str(caps.get("browserName", "")).lower()
        for browser, hints in BROWSER_NAME_HINTS.items():
            if any(hint in name for hint in hints):
                counts[browser] += 1
                break
    return counts


def _compose_cmd(compose_files, project_dir):
    cmd = ["docker", "compose"]
    for f in compose_files:
        cmd += ["-f", f]
    if project_dir:
        cmd += ["--project-directory", project_dir]
    return cmd


def _parse_compose_ps_json(stdout):
    """`docker compose ps --format json`'s exact output shape depends on
    the installed Compose CLI version: pre-2.21 emits one JSON array;
    2.21+ emits JSON Lines (one bare object per line, no brackets or
    commas) — see docker/compose issues #10958 and #11784, both
    confirmed live bug reports about this exact behavior change, not a
    guess. Handle both shapes rather than betting on one."""
    stdout = stdout.strip()
    if not stdout:
        return []
    try:
        parsed = json.loads(stdout)
        if isinstance(parsed, list):
            return parsed
        if isinstance(parsed, dict):
            return [parsed]
    except json.JSONDecodeError:
        pass
    entries = []
    for line in stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            entries.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return entries


def get_running_replicas(compose_files, project_dir, timeout=30):
    """Returns {service_name: running_container_count}. Returns an empty
    dict (not a crash) on any failure — e.g. Docker daemon not running —
    same 'skip this poll' posture as fetch_grid_state."""
    cmd = _compose_cmd(compose_files, project_dir) + ["ps", "--format", "json"]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=True)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, FileNotFoundError) as e:
        log(f"WARNING: `docker compose ps` failed: {e}")
        return {}
    counts = {}
    for entry in _parse_compose_ps_json(result.stdout):
        service = entry.get("Service")
        if not service:
            continue
        counts[service] = counts.get(service, 0) + 1
    return counts


def compute_scale_targets(queued_by_browser, current_replicas, min_replicas, max_replicas,
                          sessions_per_node, grid_idle):
    """Pure decision logic (no I/O) — kept separate from fetch/apply so it
    can be exercised with synthetic fixtures without Docker or a live
    grid. Returns {pool_service: desired_replica_count}.

    Scale-up: immediate, sized to clear the queue in one step
    (ceil(queued / sessions_per_node) additional nodes) — reacts fast on
    purpose, queued test sessions are actively costing wall-clock time.
    Scale-down: only when grid_idle is True (see module docstring's
    "KNOWN LIMITATION" for why this is grid-wide, not per-browser), and
    only by one replica per call — the caller is expected to call this
    once per cooldown window, so this naturally paces scale-down to one
    step per cooldown rather than an instant drop to min_replicas.
    """
    targets = {}
    for browser, service in POOL_SERVICES.items():
        current = current_replicas.get(service, 0)
        queued = queued_by_browser.get(browser, 0)
        if queued > 0:
            additional = -(-queued // sessions_per_node)  # ceil division, no float rounding surprises
            desired = current + additional
        elif grid_idle and current > min_replicas:
            desired = current - 1
        else:
            desired = current
        targets[service] = max(min_replicas, min(max_replicas, desired))
    return targets


def apply_scale_targets(targets, compose_files, project_dir, dry_run, timeout=120):
    changed = {svc: n for svc, n in targets.items()}
    if not changed:
        return
    if dry_run:
        log(f"DRY RUN: would scale {changed}")
        return
    cmd = _compose_cmd(compose_files, project_dir) + ["up", "-d", "--no-recreate"]
    for service, count in changed.items():
        cmd += ["--scale", f"{service}={count}"]
    # Only bring up the pool services themselves — omitting explicit
    # service names here would otherwise (re)apply `up` to every service
    # in the merged files, including the debug chrome/firefox/edge nodes
    # and selenium-hub, which this script has no business touching.
    cmd += list(POOL_SERVICES.values())
    log(f"Scaling: {changed} — running: {' '.join(cmd)}")
    try:
        subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=True)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, FileNotFoundError) as e:
        stderr = getattr(e, "stderr", "") or ""
        log(f"WARNING: scaling command failed: {e} {stderr}")


def default_compose_files(project_dir):
    import os
    files = ["docker-compose.yml"]
    override = os.path.join(project_dir or ".", "docker-compose.override.yml")
    if os.path.exists(override):
        files.append("docker-compose.override.yml")
    files.append("docker-compose.autoscale.yml")
    return files


def run_once(args, idle_since):
    grid_data = fetch_grid_state(args.hub_url)
    queued_by_browser = count_queued_by_browser(grid_data)
    session_count = (grid_data or {}).get("grid", {}).get("sessionCount", None)

    now = time.monotonic()
    if session_count == 0:
        if idle_since is None:
            idle_since = now
    else:
        idle_since = None
    grid_idle = idle_since is not None and (now - idle_since) >= args.scale_down_cooldown

    current_replicas = get_running_replicas(args.compose_files, args.project_dir)
    targets = compute_scale_targets(
        queued_by_browser, current_replicas, args.min_replicas, args.max_replicas,
        args.sessions_per_node, grid_idle,
    )
    to_apply = {svc: n for svc, n in targets.items() if n != current_replicas.get(svc, 0)}

    total_queued = sum(queued_by_browser.values())
    log(f"queued={queued_by_browser} sessions={session_count} current={current_replicas} "
        f"grid_idle={grid_idle} targets={targets}")
    if to_apply:
        apply_scale_targets(to_apply, args.compose_files, args.project_dir, args.dry_run)
    elif total_queued == 0 and session_count != 0:
        pass  # nothing queued, grid busy but not idle-cooled-down yet — no action, expected
    return idle_since


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--hub-url", default=DEFAULT_HUB_URL,
                        help=f"Selenium Grid hub base URL (default: {DEFAULT_HUB_URL})")
    parser.add_argument("--interval", type=float, default=15.0,
                        help="Seconds between polls in daemon mode (default: 15)")
    parser.add_argument("--scale-down-cooldown", type=float, default=120.0,
                        help="Seconds the whole grid must be continuously idle before scaling any pool down by one replica (default: 120)")
    parser.add_argument("--min-replicas", type=int, default=0,
                        help="Floor per browser pool (default: 0 — pool nodes are purely on-demand; the always-on debug chrome/firefox/edge nodes in docker-compose.yml are untouched by this script and still provide a baseline)")
    parser.add_argument("--max-replicas", type=int, default=5,
                        help="Ceiling per browser pool, guards host CPU/RAM (default: 5)")
    parser.add_argument("--sessions-per-node", type=int, default=3,
                        help="Must match SE_NODE_MAX_SESSIONS on the pool node images (default: 3, matching docker-compose.yml's existing nodes)")
    parser.add_argument("--project-dir", default=None,
                        help="Passed to `docker compose --project-directory` (default: current directory)")
    parser.add_argument("--compose-files", default=None,
                        help="Comma-separated compose file list, overriding the auto-detected default")
    parser.add_argument("--once", action="store_true", help="Poll once, apply if needed, then exit (for cron)")
    parser.add_argument("--dry-run", action="store_true", help="Log what would be scaled without touching containers")
    args = parser.parse_args()

    args.compose_files = (args.compose_files.split(",") if args.compose_files
                          else default_compose_files(args.project_dir))

    log(f"grid-autoscaler starting: hub={args.hub_url} compose_files={args.compose_files} "
        f"min={args.min_replicas} max={args.max_replicas} dry_run={args.dry_run}")

    idle_since = None
    if args.once:
        run_once(args, idle_since)
        return 0

    try:
        while True:
            idle_since = run_once(args, idle_since)
            time.sleep(args.interval)
    except KeyboardInterrupt:
        log("Stopping (Ctrl-C).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
