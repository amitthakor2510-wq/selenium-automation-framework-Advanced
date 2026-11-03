#!/usr/bin/env bash
# Pins every tag-only "FROM image:tag" line in the project's Dockerfiles to an immutable digest:
#
#     FROM eclipse-temurin:17-jdk            ->  FROM eclipse-temurin:17-jdk@sha256:<digest>
#
# Why: a tag can be re-pushed (a compromised or simply updated image changes under you without any
# diff in this repo); a digest cannot. Third-party GitHub Actions are already SHA-pinned; this is the
# same rule for base images. Dependabot's "docker" ecosystem (.github/dependabot.yml) then proposes
# new digests as reviewable PRs.
#
# Usage:
#   Scripts/pin-docker-digests.sh            # rewrite Dockerfile + Dockerfile.dashboard in place
#   Scripts/pin-docker-digests.sh --check    # list unpinned FROM lines, exit 1 if any (no changes)
#   Scripts/pin-docker-digests.sh Dockerfile.foo ...   # explicit files
#
# Needs network access to the registry and ONE of: docker buildx, docker, crane. Run it once on a
# machine that has Docker, review `git diff`, commit.
set -euo pipefail
cd "$(dirname "$0")/.."

CHECK=0
FILES=()
for arg in "$@"; do
  case "$arg" in
    --check) CHECK=1 ;;
    -*) echo "Unknown option: $arg" >&2; exit 2 ;;
    *) FILES+=("$arg") ;;
  esac
done
[ "${#FILES[@]}" -eq 0 ] && FILES=(Dockerfile Dockerfile.dashboard)

resolve_digest() {
  local ref="$1" out=""
  if command -v crane >/dev/null 2>&1; then
    out=$(crane digest "$ref" 2>/dev/null || true)
  fi
  if [ -z "$out" ] && command -v docker >/dev/null 2>&1; then
    out=$(docker buildx imagetools inspect "$ref" --format '{{json .Manifest.Digest}}' 2>/dev/null | tr -d '"' || true)
  fi
  case "$out" in sha256:*) printf '%s' "$out" ;; *) return 1 ;; esac
}

UNPINNED=0
for f in "${FILES[@]}"; do
  [ -f "$f" ] || continue
  tmp=$(mktemp)
  while IFS= read -r line || [ -n "$line" ]; do
    # Only real FROM instructions that have no @sha256: yet and are not a build-stage reference.
    if [[ $line =~ ^FROM[[:space:]]+([^[:space:]@]+)([[:space:]]+AS[[:space:]]+[A-Za-z0-9_.-]+)?[[:space:]]*$ ]]; then
      image="${BASH_REMATCH[1]}"
      suffix="${BASH_REMATCH[2]:-}"
      if [ "$image" = "scratch" ] || grep -qE "^FROM[[:space:]]+.*[[:space:]]AS[[:space:]]+${image}([[:space:]]|$)" "$f"; then
        printf '%s\n' "$line" >> "$tmp"; continue
      fi
      UNPINNED=$((UNPINNED+1))
      if [ "$CHECK" -eq 1 ]; then
        echo "  ✗ $f: '$image' is tag-only (no @sha256 digest)"
        printf '%s\n' "$line" >> "$tmp"; continue
      fi
      if digest=$(resolve_digest "$image"); then
        printf 'FROM %s@%s%s\n' "$image" "$digest" "$suffix" >> "$tmp"
        echo "  ✓ $f: $image -> $digest"
      else
        echo "  ! $f: could not resolve a digest for '$image' (no registry access, or no crane/docker?) - left as is" >&2
        printf '%s\n' "$line" >> "$tmp"
      fi
    else
      printf '%s\n' "$line" >> "$tmp"
    fi
  done < "$f"
  if [ "$CHECK" -eq 0 ]; then cat "$tmp" > "$f"; fi
  rm -f "$tmp"
done

if [ "$CHECK" -eq 1 ]; then
  [ "$UNPINNED" -eq 0 ] && { echo "All base images are pinned by digest."; exit 0; }
  echo "$UNPINNED base image(s) not pinned - run Scripts/pin-docker-digests.sh"; exit 1
fi
exit 0
