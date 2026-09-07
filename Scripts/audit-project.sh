#!/usr/bin/env bash
# Usage:
#   Scripts/audit-project.sh                # run every check
#   Scripts/audit-project.sh --fix-packages  # also auto-move any .java file
#                                             # whose package doesn't match
#                                             # its directory to the right spot
#   Scripts/audit-project.sh --quick         # skip mvn compile (fast, no
#                                             # network/Maven-Central needed)
#
# Catches the class of bug `mvn compile` does NOT catch: the Maven compiler
# plugin passes an explicit file list to javac, so a .java file sitting in
# the wrong directory for its own `package` declaration still compiles
# clean — it just silently breaks IDE navigation, Test Impact Analysis's
# FQCN resolution (com.automation.core.tia), and anyone's mental model of
# "the directory tree is the package tree". This has bitten the repo twice
# now (LoginAndForgotPasswordKeywordTest during the indiaai->SAHMAT rename,
# then DemoQaHomePagePerfTest/JsonPlaceholderApiPerfTest/JsonPlaceholderApiTest
# all shuffled into each other's folders when the perf/jsonplaceholder work
# landed) — this script exists so the next one gets caught before a human
# has to go looking for it again.
#
# Dependency-free (grep/awk/find only — no python, no yq, no jq) so it runs
# unmodified on GitHub's runners, a bare-metal Jenkins host, and GitLab's
# shared/Docker runners alike, same convention as enabled-sites.sh.
# xmllint/mvn are used only if present, and each is individually skippable.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

FIX_PACKAGES=0
QUICK=0
for arg in "$@"; do
  case "$arg" in
    --fix-packages) FIX_PACKAGES=1 ;;
    --quick) QUICK=1 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

FAILED=0
fail() { echo "  ✗ $1"; FAILED=1; }
ok()   { echo "  ✓ $1"; }

echo "=== 1. Package/directory mismatches ==="
MISMATCHES=0
while IFS= read -r -d '' file; do
  pkg=$(grep -m1 -E '^package[[:space:]]+[A-Za-z0-9_.]+[[:space:]]*;' "$file" \
        | sed -E 's/^package[[:space:]]+([A-Za-z0-9_.]+)[[:space:]]*;.*/\1/')
  [ -z "$pkg" ] && { fail "$file — no package declaration found"; MISMATCHES=$((MISMATCHES+1)); continue; }
  expected_suffix=$(echo "$pkg" | tr '.' '/')
  dir=$(dirname "$file")
  case "$dir" in
    */"$expected_suffix") ;; # ends with the expected suffix — fine
    *)
      MISMATCHES=$((MISMATCHES+1))
      fail "$file — package '$pkg' expects dir '.../$expected_suffix', found in '$dir'"
      if [ "$FIX_PACKAGES" -eq 1 ]; then
        # Find this file's own source root (src/main/java or src/test/java)
        # by walking up until we hit a "java" dir directly under "main" or "test".
        srcroot=""
        walk="$dir"
        while [ "$walk" != "." ] && [ "$walk" != "/" ]; do
          case "$walk" in
            src/main/java|*/src/main/java) srcroot="$walk"; break ;;
            src/test/java|*/src/test/java) srcroot="$walk"; break ;;
          esac
          walk=$(dirname "$walk")
        done
        if [ -z "$srcroot" ]; then
          echo "    -> could not resolve a src/{main,test}/java root above $dir — not auto-moving"
        else
          target="$srcroot/$expected_suffix"
          mkdir -p "$target"
          mv -n "$file" "$target/"
          echo "    -> moved to $target/$(basename "$file")"
        fi
      fi
      ;;
  esac
done < <(find src -name "*.java" -print0)
[ "$MISMATCHES" -eq 0 ] && ok "0 mismatches across all .java files"

echo "=== 2. Duplicate top-level class filenames ==="
DUPES=$(find src -name "*.java" -exec basename {} \; | sort | uniq -d)
if [ -n "$DUPES" ]; then
  fail "duplicate filenames found:"
  echo "$DUPES" | sed 's/^/      /'
else
  ok "no duplicate class filenames"
fi

echo "=== 3. Empty catch blocks (real Java, not embedded JS strings) ==="
# Real Java catch clauses always have "Type varName" inside the parens;
# JS shorthand embedded in executeScript() strings (catch(e) {}) has just
# one bare identifier, so requiring two space-separated tokens tells them
# apart without needing to parse string-literal context.
EMPTY_CATCH=$(grep -rnE 'catch[[:space:]]*\([A-Za-z_][A-Za-z0-9_.<>\[\]]*[[:space:]]+[A-Za-z_][A-Za-z0-9_]*\)[[:space:]]*\{[[:space:]]*\}' --include='*.java' src)
if [ -n "$EMPTY_CATCH" ]; then
  fail "possible empty catch block(s):"
  echo "$EMPTY_CATCH" | sed 's/^/      /'
else
  ok "no empty catch blocks"
fi

echo "=== 4. TODO / FIXME markers ==="
TODO_COUNT=$(grep -rn 'TODO\|FIXME' --include='*.java' src | wc -l | tr -d ' ')
if [ "$TODO_COUNT" -gt 0 ]; then
  echo "  ! $TODO_COUNT TODO/FIXME marker(s) — not a failure, just flagging:"
  grep -rn 'TODO\|FIXME' --include='*.java' src | sed 's/^/      /'
else
  ok "0 TODO/FIXME markers"
fi

echo "=== 5. Suspicious string equality (== on String literals) ==="
STR_EQ=$(grep -rnE '[A-Za-z0-9_]+[[:space:]]*==[[:space:]]*"' --include='*.java' src)
if [ -n "$STR_EQ" ]; then
  fail "possible == string comparison(s):"
  echo "$STR_EQ" | sed 's/^/      /'
else
  ok "no == string comparisons"
fi

echo "=== 6. XML validity (all *.xml, testng suites included) ==="
if command -v xmllint &> /dev/null; then
  XML_BAD=0
  while IFS= read -r -d '' f; do
    if ! xmllint --noout "$f" 2>/tmp/audit-xml-err.log; then
      fail "$f"; cat /tmp/audit-xml-err.log | sed 's/^/      /'
      XML_BAD=1
    fi
  done < <(find . -name "*.xml" -not -path "./.git/*" -print0)
  [ "$XML_BAD" -eq 0 ] && ok "all XML files parse cleanly"
else
  echo "  ! xmllint not installed — skipping (apt install libxml2-utils)"
fi

echo "=== 7. Shell script syntax (bash -n) ==="
SH_BAD=0
while IFS= read -r -d '' f; do
  if ! bash -n "$f" 2>/tmp/audit-sh-err.log; then
    fail "$f"; cat /tmp/audit-sh-err.log | sed 's/^/      /'
    SH_BAD=1
  fi
done < <(find . -name "*.sh" -not -path "./.git/*" -print0)
[ "$SH_BAD" -eq 0 ] && ok "all shell scripts parse cleanly"

echo "=== 8. Dead duplicate config files (same filename inside AND outside config/) ==="
# Only flags a top-level properties file that SHADOWS a same-named one
# under config/ (the actual bug pattern seen twice: SAHMAT's dead
# objectrepository/SAHMAT.properties, then the stray top-level
# jsonplaceholder.properties duplicating config/jsonplaceholder.properties).
# Files like logging.properties/allure.properties/reportportal.properties
# genuinely belong at the resources root for their own libraries — those
# are correct and must NOT be flagged, hence the same-filename-in-both-
# places condition instead of "any top-level properties file".
STRAY_DUPES=0
while IFS= read -r -d '' f; do
  name=$(basename "$f")
  if [ -f "src/test/resources/config/$name" ]; then
    fail "src/test/resources/$name duplicates src/test/resources/config/$name — ConfigReader only loads the config/ copy, the top-level one is dead"
    STRAY_DUPES=1
  fi
done < <(find src/test/resources -maxdepth 1 -name "*.properties" -print0)
[ "$STRAY_DUPES" -eq 0 ] && ok "no dead duplicate properties files"

if [ "$QUICK" -eq 0 ]; then
  echo "=== 9. Real compile (mvn test-compile) ==="
  if command -v mvn &> /dev/null; then
    if mvn -q -o test-compile 2>/tmp/audit-mvn-err.log; then
      ok "mvn test-compile succeeded"
    else
      fail "mvn test-compile failed — see /tmp/audit-mvn-err.log"
      tail -40 /tmp/audit-mvn-err.log | sed 's/^/      /'
    fi
  else
    echo "  ! mvn not on PATH — skipping (run with --quick to silence this)"
  fi
else
  echo "=== 9. Real compile — skipped (--quick) ==="
fi

echo ""
if [ "$FAILED" -ne 0 ]; then
  echo "[audit-project] One or more checks failed — see ✗ lines above."
  [ "$FIX_PACKAGES" -eq 0 ] && [ "$MISMATCHES" -gt 0 ] && \
    echo "[audit-project] Re-run with --fix-packages to auto-move the mismatched files."
  exit 1
fi
echo "[audit-project] All checks passed."
exit 0
