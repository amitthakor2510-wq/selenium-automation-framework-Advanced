#!/usr/bin/env bash
# Usage:
#   Scripts/test-gate.sh record <label> [reports-dir]   # after each mvn test run
#   Scripts/test-gate.sh check  [gate-dir]              # once, in the final gate job
#
# Why this exists: every pipeline passes -Dmaven.test.failure.ignore=true so
# the report stages still run after a failing test. That also means mvn exits 0
# and the pipeline goes green with failing tests. This script is the missing
# gate, shared by GitLab CI, Jenkins and GitHub Actions so the rule lives in
# exactly one place.
#
#   record  reads <reports-dir>/testng-results.xml (default
#           target/surefire-reports) and writes ONE small file,
#           target/gate/<label>.txt. It never fails the job, so reports and
#           artifacts still upload. Each matrix leg uses its own <label>
#           (e.g. SAHMAT-chrome), so the files have unique names and survive
#           GitLab's artifact merge, unlike testng-results.xml, where only one
#           leg's copy survives.
#   check   reads every target/gate/*.txt and exits 1 if any leg has failures,
#           no results, zero tests, or nothing passed.
#
# Env knobs for check:
#   GATE_FAIL_ON_SKIPPED=true   also fail when any test was skipped (default false)
#   GATE_ALLOW_EMPTY=true       do not fail when no gate files exist at all (default false)
#
# Dependency-free (bash/grep/sed only), like Scripts/enabled-sites.sh.
set -uo pipefail
cd "$(dirname "$0")/.."

cmd="${1:-}"
shift || true

attr() { # attr <name> <tag-string> -> integer, 0 if absent
    local v
    v=$(printf '%s' "$2" | grep -o " $1=\"[0-9]*\"" | grep -o '[0-9]*' | head -1)
    printf '%s' "${v:-0}"
}

field() { # field <name> <file> -> value of name=value from a gate file
    grep -o "$1=[^ ]*" "$2" | head -1 | cut -d= -f2
}

record() {
    local label="${1:?usage: test-gate.sh record <label> [reports-dir]}"
    label="${label// /_}"
    local dir="${2:-target/surefire-reports}"
    local out="${GATE_DIR:-target/gate}"
    local safe="${label//[^A-Za-z0-9._-]/_}"
    local f="$dir/testng-results.xml"
    mkdir -p "$out"

    if [ ! -f "$f" ]; then
        echo "label=$label status=missing" > "$out/$safe.txt"
        echo "[gate] $label: no testng-results.xml in $dir"
        return 0
    fi
    local tag
    tag=$(grep -m1 -o '<testng-results[^>]*>' "$f" || true)
    if [ -z "$tag" ]; then
        echo "label=$label status=unreadable" > "$out/$safe.txt"
        echo "[gate] $label: could not read the <testng-results> tag in $f"
        return 0
    fi
    local total passed failed skipped
    total=$(attr total "$tag"); passed=$(attr passed "$tag")
    failed=$(attr failed "$tag"); skipped=$(attr skipped "$tag")
    # TestNG's own "total" attribute also counts configuration (@Before/@After)
    # methods, so it is larger than the number of tests that ran. Count real
    # test results instead.
    total=$((passed + failed + skipped))
    echo "label=$label status=ok total=$total passed=$passed failed=$failed skipped=$skipped" > "$out/$safe.txt"
    echo "[gate] $label: total=$total passed=$passed failed=$failed skipped=$skipped"
    return 0
}

check() {
    local dir="${1:-${GATE_DIR:-target/gate}}"
    local bad=0 n=0 f
    local lines="| Leg | Total | Passed | Failed | Skipped | Verdict |\n|---|---|---|---|---|---|\n"

    echo "======= Test Gate ======="
    shopt -s nullglob
    for f in "$dir"/*.txt; do
        n=$((n + 1))
        local label status total passed failed skipped verdict="OK"
        label=$(field label "$f"); status=$(field status "$f")
        if [ "$status" != "ok" ]; then
            verdict="FAIL (results $status)"; bad=$((bad + 1))
            printf '  %-32s %s\n' "$label" "$verdict"
            lines+="| $label | - | - | - | - | $verdict |\n"
            continue
        fi
        total=$(field total "$f"); passed=$(field passed "$f")
        failed=$(field failed "$f"); skipped=$(field skipped "$f")
        if   [ "$failed" -gt 0 ];  then verdict="FAIL ($failed failed)"
        elif [ "$total" -eq 0 ];   then verdict="FAIL (0 tests ran)"
        elif [ "$passed" -eq 0 ];  then verdict="FAIL (nothing passed)"
        elif [ "${GATE_FAIL_ON_SKIPPED:-false}" = "true" ] && [ "$skipped" -gt 0 ]; then
            verdict="FAIL ($skipped skipped)"
        fi
        [ "$verdict" != "OK" ] && bad=$((bad + 1))
        printf '  %-32s total=%s passed=%s failed=%s skipped=%s  %s\n' \
            "$label" "$total" "$passed" "$failed" "$skipped" "$verdict"
        lines+="| $label | $total | $passed | $failed | $skipped | $verdict |\n"
    done

    if [ "$n" -eq 0 ]; then
        if [ "${GATE_ALLOW_EMPTY:-false}" = "true" ]; then
            echo "No gate files in $dir (GATE_ALLOW_EMPTY=true) - passing."
            return 0
        fi
        echo "FAIL: no gate files in $dir - the test jobs never recorded results."
        return 1
    fi

    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        printf '### Test Gate\n%b\n' "$lines" >> "$GITHUB_STEP_SUMMARY"
    fi
    if [ "$bad" -gt 0 ]; then
        echo "GATE FAILED: $bad of $n leg(s) have failing or missing results."
        return 1
    fi
    echo "GATE PASSED: all $n leg(s) clean."
    return 0
}

case "$cmd" in
    record) record "$@" ;;
    check)  check "$@" ;;
    *) sed -n '2,5p' "$0"; exit 2 ;;
esac
