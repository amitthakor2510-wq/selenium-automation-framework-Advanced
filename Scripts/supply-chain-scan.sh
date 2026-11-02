#!/usr/bin/env bash
# Supply-chain and container scans, one entry point for GitLab CI, GitHub Actions and Jenkins.
#
#   Scripts/supply-chain-scan.sh hadolint                       lint every Dockerfile*          (hard gate)
#   Scripts/supply-chain-scan.sh trivy-config                   Dockerfile misconfigurations    (gate: HIGH/CRITICAL)
#   Scripts/supply-chain-scan.sh trivy-image <Dockerfile> <tag> build the image, scan it        (report-only by default)
#   Scripts/supply-chain-scan.sh trivy-sbom <bom.json>          scan a CycloneDX SBOM for CVEs  (report-only by default)
#   Scripts/supply-chain-scan.sh semgrep                        SAST for the Python code        (local rules gate)
#
# Tools are downloaded once into .security-tools/ at a pinned version and verified against a
# SHA-256 hash that is stored in this file, so a tampered download is rejected. A copy already on
# PATH is used instead when SECURITY_USE_PATH_TOOLS=true. Linux x86_64 only for the downloads.
#
# Environment (all optional):
#   TRIVY_IMAGE_EXIT_CODE / TRIVY_SBOM_EXIT_CODE   0 = report only (default), 1 = fail on findings
#   TRIVY_SEVERITY                                  default HIGH,CRITICAL
#   SECURITY_REQUIRE_DOCKER=true                    fail (instead of skip) when Docker is unreachable
#   SEMGREP_REGISTRY=false                          skip the registry rule packs (offline runners)
#   SECURITY_REPORT_DIR                             default target/security
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

HADOLINT_VERSION="2.15.1"
HADOLINT_SHA256="c7187db94eeeeca956519a6af171adc31453941a1e777961f6e680f697c8c507"
TRIVY_VERSION="0.76.1"
TRIVY_SHA256="c7cbe3137fb8a48a17574bede3dd6beb16742d1e9772929940fc90082c39d9c0"
SEMGREP_VERSION="1.183.0"

TOOLS_DIR="${SECURITY_TOOLS_DIR:-.security-tools}"
REPORT_DIR="${SECURITY_REPORT_DIR:-target/security}"
TRIVY_SEVERITY="${TRIVY_SEVERITY:-HIGH,CRITICAL}"
mkdir -p "$TOOLS_DIR/bin" "$REPORT_DIR"
case "$TOOLS_DIR" in /*) TOOLS_ABS="$TOOLS_DIR" ;; *) TOOLS_ABS="$PWD/$TOOLS_DIR" ;; esac
export PATH="$TOOLS_ABS/bin:$PATH"

log() { printf '======= %s =======\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

fetch() { # url dest
  if command -v curl >/dev/null 2>&1; then curl -fsSL --retry 3 -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then wget -q -O "$2" "$1"
  else die "neither curl nor wget is installed"; fi
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

verify() { # file expected-sha256
  local actual; actual="$(sha256_of "$1")"
  [ "$actual" = "$2" ] || die "checksum mismatch for $1 (expected $2, got $actual) - refusing to run it"
}

require_linux_x64() {
  [ "$(uname -s)-$(uname -m)" = "Linux-x86_64" ] \
    || die "automatic download supports Linux x86_64 only; install $1 yourself and set SECURITY_USE_PATH_TOOLS=true"
}

use_path_tool() { [ "${SECURITY_USE_PATH_TOOLS:-false}" = "true" ] && command -v "$1" >/dev/null 2>&1; }

ensure_hadolint() {
  use_path_tool hadolint && return 0
  if [ ! -x "$TOOLS_DIR/bin/hadolint" ]; then
    require_linux_x64 hadolint
    log "Downloading hadolint $HADOLINT_VERSION"
    fetch "https://github.com/hadolint/hadolint/releases/download/v${HADOLINT_VERSION}/hadolint-linux-x86_64" "$TOOLS_DIR/hadolint.tmp"
    verify "$TOOLS_DIR/hadolint.tmp" "$HADOLINT_SHA256"
    chmod +x "$TOOLS_DIR/hadolint.tmp"; mv "$TOOLS_DIR/hadolint.tmp" "$TOOLS_DIR/bin/hadolint"
  fi
}

ensure_trivy() {
  use_path_tool trivy && return 0
  if [ ! -x "$TOOLS_DIR/bin/trivy" ]; then
    require_linux_x64 trivy
    log "Downloading trivy $TRIVY_VERSION"
    fetch "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_Linux-64bit.tar.gz" "$TOOLS_DIR/trivy.tgz"
    verify "$TOOLS_DIR/trivy.tgz" "$TRIVY_SHA256"
    tar -xzf "$TOOLS_DIR/trivy.tgz" -C "$TOOLS_DIR/bin" trivy
    rm -f "$TOOLS_DIR/trivy.tgz"
  fi
}

ensure_semgrep() {
  use_path_tool semgrep && return 0
  if [ ! -x "$TOOLS_DIR/semgrep-venv/bin/semgrep" ]; then
    command -v python3 >/dev/null 2>&1 || die "python3 is required for semgrep"
    log "Installing semgrep $SEMGREP_VERSION into $TOOLS_DIR/semgrep-venv"
    python3 -m venv "$TOOLS_DIR/semgrep-venv" || die "python3 -m venv failed (on Debian/Ubuntu: apt install python3-venv)"
    "$TOOLS_DIR/semgrep-venv/bin/pip" install --quiet "semgrep==${SEMGREP_VERSION}"
  fi
  [ -x "$TOOLS_DIR/semgrep-venv/bin/semgrep" ] && export PATH="$TOOLS_ABS/semgrep-venv/bin:$PATH"
  return 0
}

dockerfiles() {
  find . -maxdepth 3 -type f \( -name 'Dockerfile' -o -name 'Dockerfile.*' -o -name '*.Dockerfile' \) \
    -not -path './.git/*' -not -path './target/*' -not -path './.security-tools/*' -not -path './.m2/*' \
    -not -path './node_modules/*' | sed 's#^\./##' | sort
}

docker_ok() { command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; }

cmd_hadolint() {
  ensure_hadolint
  hadolint --version
  local rc=0 f
  for f in $(dockerfiles); do
    log "hadolint $f"
    hadolint --config .hadolint.yaml "$f" || rc=1
    hadolint --config .hadolint.yaml --no-fail --format json "$f" > "$REPORT_DIR/hadolint-$(echo "$f" | tr '/' '_').json" || true
  done
  [ "$rc" -eq 0 ] && echo "hadolint: clean" || echo "hadolint: findings at or above the configured threshold (see .hadolint.yaml)"
  return "$rc"
}

trivy_common=(--ignorefile .trivyignore.yaml --skip-dirs .git --skip-dirs .idea --skip-dirs .security-tools --skip-dirs target --skip-dirs .m2)

cmd_trivy_config() {
  ensure_trivy
  log "trivy config (all severities, report)"
  trivy config "${trivy_common[@]}" --severity LOW,MEDIUM,HIGH,CRITICAL --format table --output "$REPORT_DIR/trivy-config.txt" . || true
  cat "$REPORT_DIR/trivy-config.txt"
  log "trivy config (gate: $TRIVY_SEVERITY)"
  trivy config "${trivy_common[@]}" --severity "$TRIVY_SEVERITY" --exit-code 1 --quiet .
}

cmd_trivy_image() {
  local dockerfile="${1:-}" tag="${2:-}"
  [ -n "$dockerfile" ] && [ -n "$tag" ] || die "usage: trivy-image <Dockerfile> <image:tag>"
  if ! docker_ok; then
    [ "${SECURITY_REQUIRE_DOCKER:-false}" = "true" ] && die "Docker is not reachable"
    echo "WARNING: Docker is not reachable - skipping the image scan of $dockerfile"; return 0
  fi
  ensure_trivy
  log "docker build $dockerfile -> $tag"
  docker build -f "$dockerfile" -t "$tag" .
  local safe; safe="$(echo "$tag" | tr '/:' '__')"
  log "trivy image $tag (SBOM + report)"
  trivy image --format cyclonedx --output "$REPORT_DIR/sbom-image-$safe.cdx.json" "$tag" || true
  trivy image --ignore-unfixed --severity LOW,MEDIUM,HIGH,CRITICAL --format table --output "$REPORT_DIR/trivy-image-$safe.txt" "$tag" || true
  tail -n 40 "$REPORT_DIR/trivy-image-$safe.txt" || true
  log "trivy image $tag (gate: $TRIVY_SEVERITY, exit code ${TRIVY_IMAGE_EXIT_CODE:-0})"
  trivy image --ignore-unfixed --severity "$TRIVY_SEVERITY" --exit-code "${TRIVY_IMAGE_EXIT_CODE:-0}" --quiet "$tag"
}

cmd_trivy_sbom() {
  local bom="${1:-}"
  [ -f "$bom" ] || die "usage: trivy-sbom <bom.json> (file not found: ${bom:-none})"
  ensure_trivy
  log "trivy sbom $bom (gate: $TRIVY_SEVERITY, exit code ${TRIVY_SBOM_EXIT_CODE:-0})"
  trivy sbom --ignore-unfixed --severity "$TRIVY_SEVERITY" --format table --output "$REPORT_DIR/trivy-sbom.txt" \
    --exit-code "${TRIVY_SBOM_EXIT_CODE:-0}" "$bom" || { cat "$REPORT_DIR/trivy-sbom.txt" 2>/dev/null || true; return 1; }
  cat "$REPORT_DIR/trivy-sbom.txt"
}

cmd_semgrep() {
  ensure_semgrep
  semgrep --version
  local targets=() t
  for t in Scripts .github Dockerfile Dockerfile.dashboard docker-compose.yml docker-compose.dashboard.yml; do
    [ -e "$t" ] && targets+=("$t")
  done
  log "semgrep: rule tests (offline)"
  semgrep --test semgrep-rules --metrics=off --disable-version-check
  log "semgrep: project rules (gate)"
  semgrep scan --config semgrep-rules/dashboard-security.yml --error --metrics=off --disable-version-check \
    --json --output "$REPORT_DIR/semgrep-local.json" "${targets[@]}" || { semgrep scan --config semgrep-rules/dashboard-security.yml --metrics=off --disable-version-check "${targets[@]}" || true; return 1; }
  echo "semgrep project rules: no findings"
  if [ "${SEMGREP_REGISTRY:-true}" = "true" ]; then
    log "semgrep: registry packs (report-only)"
    semgrep scan --config p/python --config p/github-actions --config p/dockerfile --metrics=off --disable-version-check \
      --json --output "$REPORT_DIR/semgrep-registry.json" "${targets[@]}" src/main \
      || echo "WARNING: registry packs could not run (network?). Set SEMGREP_REGISTRY=false to skip them."
    if [ -f "$REPORT_DIR/semgrep-registry.json" ]; then
      python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print('semgrep registry packs:', len(d.get('results', [])), 'finding(s) - see', sys.argv[1])" "$REPORT_DIR/semgrep-registry.json"
    fi
  fi
}

case "${1:-}" in
  hadolint)     cmd_hadolint ;;
  trivy-config) cmd_trivy_config ;;
  trivy-image)  shift; cmd_trivy_image "$@" ;;
  trivy-sbom)   shift; cmd_trivy_sbom "$@" ;;
  semgrep)      cmd_semgrep ;;
  *) sed -n '2,12p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
