#!/usr/bin/env bash
# Usage: ./Scripts/record.sh <site> <TestCaseName> [start-path-or-url] [extra RecorderCli options...]
# Example: ./Scripts/record.sh saucedemo TC_Login /
#          ./Scripts/record.sh SAHMAT TC_Booking / --overwrite --no-auto-assert
#
# Opens a visible browser on the site, records what you do, then writes it as a keyword-driven test:
#   * rows in   src/test/resources/testdata/keyword/<site>_recorded_keywords.csv
#   * locators in src/test/resources/objectrepository/<site>.properties (primary + fallbacks)
# and finally replays the new test case in a fresh browser to prove it works.
# Stop with the "Stop" button in the page toolbar, or press ENTER in this terminal.
# Passwords are never written to the script - the script gets ${env:...} placeholders and this
# tool tells you which variables to set. Full guide: RECORD_AND_PLAYBACK.md

set -e

if [ "$#" -lt 2 ]; then
  sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi

SITE="$1"
NAME="$2"
START="${3:-/}"
shift $(( $# < 3 ? $# : 3 ))

cd "$(dirname "$0")/.."

# Recording needs a visible browser, so make sure a headless setting from the environment/config
# cannot silently turn it off.
mvn -q test-compile exec:java@recorder -Precorder -Dsite="$SITE" -Dheadless=false \
  -Dexec.args="--name $NAME --url $START $*"
