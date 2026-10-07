#!/usr/bin/env bash
# One-command API testing (no Java to write). Runs requests described in spec files, or generated from
# an OpenAPI/Swagger document, and writes a one-page HTML report: target/api-report/index.html
#
#   ./Scripts/api-test.sh --init                       create src/test/resources/apitests/my-api.yml to edit
#   ./Scripts/api-test.sh                              run every spec in src/test/resources/apitests
#   ./Scripts/api-test.sh --openapi URL_OR_FILE        generate tests from Swagger/OpenAPI and run them
#   ./Scripts/api-test.sh --spec FILE[,FILE]           run specific spec file(s)
#   ./Scripts/api-test.sh --example                    demo run against the public JSONPlaceholder API
#
# Options:
#   --base-url URL      point every spec at this server (overrides baseUrl: in the files)
#   --token TOKEN       bearer token for requests whose case has no auth: of its own
#   --env NAME          use environments.NAME from the spec file(s)
#   --tags a,b          only cases with one of these tags        --exclude-tags a,b   skip those tags
#   --name TEXT         only cases whose name contains TEXT      --file TEXT          only spec files containing TEXT
#   --negative          with --openapi: also generate "no credentials" / "empty body" tests
#   --var NAME=VALUE    set ${NAME} (repeatable)                 --max-time MS        fail responses slower than MS
#   --retry N           retry failed calls N times               --open               open the report afterwards
#   --allure            also open the Allure report (mvn allure:serve)
#   -h | --help
#
# Exit code: 0 = every request passed, 1 = at least one failed, 2 = could not run.
# Secrets: put them in environment variables (${env:API_TOKEN} in the spec), not in files.
set -uo pipefail
cd "$(dirname "$0")/.."

usage() { sed -n '2,/^set -uo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; }

MAVEN_ARGS=()
OPEN=false
ALLURE=false
MODE=run

add() { MAVEN_ARGS+=("-D$1=$2"); }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --init)         MODE=init; shift ;;
    --example)      add api.spec src/test/resources/apitests/examples/jsonplaceholder.yml; shift ;;
    --openapi)      add api.openapi "${2:?--openapi needs a URL or file}"; shift 2 ;;
    --spec)         add api.spec "${2:?--spec needs a file}"; shift 2 ;;
    --base-url)     add api.base.url "${2:?--base-url needs a URL}"; add url "$2"; shift 2 ;;
    --token)        add api.auth.token "${2:?--token needs a value}"; shift 2 ;;
    --env)          add api.env "${2:?--env needs a name}"; shift 2 ;;
    --tags)         add api.spec.tags "${2:?--tags needs a list}"; shift 2 ;;
    --exclude-tags) add api.spec.excludeTags "${2:?--exclude-tags needs a list}"; shift 2 ;;
    --name)         add api.spec.name "${2:?--name needs text}"; shift 2 ;;
    --file)         add api.spec.file "${2:?--file needs text}"; shift 2 ;;
    --negative)     add api.openapi.negative true; shift ;;
    --var)          kv="${2:?--var needs NAME=VALUE}"; add "api.var.${kv%%=*}" "${kv#*=}"; shift 2 ;;
    --max-time)     add api.spec.maxTimeMs "${2:?--max-time needs milliseconds}"; shift 2 ;;
    --retry)        add api.retry.count "${2:?--retry needs a number}"; shift 2 ;;
    --open)         OPEN=true; shift ;;
    --allure)       ALLURE=true; shift ;;
    -h|--help)      usage; exit 0 ;;
    *)              echo "[x] Unknown option: $1  (try --help)"; exit 2 ;;
  esac
done

if [[ "$MODE" == "init" ]]; then
  target="src/test/resources/apitests/my-api.yml"
  if [[ -e "$target" ]]; then
    echo "[x] $target already exists - edit it, or delete it first."; exit 2
  fi
  mkdir -p "$(dirname "$target")"
  cp src/test/resources/apitests/_template.yml "$target"
  echo "[ok] Created $target"
  echo "     1. Edit baseUrl, auth and the tests in it (the file explains every option)."
  echo "     2. Run:  ./Scripts/api-test.sh"
  exit 0
fi

if command -v mvn >/dev/null 2>&1; then MVN=mvn
elif [[ -x ./mvnw ]]; then MVN=./mvnw
else echo "[x] Maven not found (install mvn or use the bundled ./mvnw)."; exit 2; fi

rm -rf target/api-report
echo ">>> $MVN test -Dsite=apitest -DsuiteXmlFile=testng-suites/api-spec.xml ${MAVEN_ARGS[*]:-}" | sed -E 's/(api\.auth\.token=)[^ ]+/\1***/'
"$MVN" test -B -Dsite=apitest -DsuiteXmlFile=testng-suites/api-spec.xml "${MAVEN_ARGS[@]}"
MVN_EXIT=$?

REPORT="target/api-report/index.html"
RESULTS="target/api-report/results.json"
if [[ ! -f "$RESULTS" ]]; then
  echo "[x] No report was produced (Maven exit code $MVN_EXIT). Scroll up for the build error."
  exit 2
fi

FAILED=$(grep -o '"failed":[0-9]*' "$RESULTS" | tail -1 | cut -d: -f2)
PASSED=$(grep -o '"passed":[0-9]*' "$RESULTS" | tail -1 | cut -d: -f2)
SKIPPED=$(grep -o '"skipped":[0-9]*' "$RESULTS" | tail -1 | cut -d: -f2)
echo
echo "Passed: ${PASSED:-0}   Failed: ${FAILED:-0}   Skipped: ${SKIPPED:-0}"
echo "HTML report : $(pwd)/$REPORT"
EXTENT=$(find target/extent-reports -path '*apitest*' -name index.html -newer "$RESULTS" -o -path '*apitest*' -name index.html 2>/dev/null | head -1)
[[ -n "$EXTENT" ]] && echo "Extent      : $(pwd)/$EXTENT"

if $OPEN; then
  (xdg-open "$REPORT" || open "$REPORT") >/dev/null 2>&1 || true
fi
if $ALLURE; then
  "$MVN" -q allure:serve
fi

[[ "${FAILED:-0}" == "0" ]] && exit 0 || exit 1
