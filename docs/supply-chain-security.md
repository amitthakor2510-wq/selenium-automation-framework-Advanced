# Supply-chain and container security

Five checks added on top of OWASP Dependency-Check, SpotBugs/FindSecBugs and gitleaks. Scanner logic lives in
one script, `Scripts/supply-chain-scan.sh`, so GitLab CI (`.gitlab/ci/supply-chain-security.yml`), GitHub
Actions (`.github/workflows/supply-chain-security.yml`) and the Jenkinsfile run the same commands.

| Check | What it does | Gate today |
| --- | --- | --- |
| Maven Enforcer (`pom.xml`) | Maven >= 3.6.3, Java >= 17, duplicate dependency versions, banned logging dependencies declared directly | Hard (every lifecycle build, `validate` phase) |
| Enforcer, dependency tree | Dependency convergence and transitive bans | Warnings; `-Penforce-strict` makes them errors |
| Hadolint | Lints every `Dockerfile*` (`.hadolint.yaml`) | Hard |
| Trivy config | Dockerfile misconfigurations (`.trivyignore.yaml`) | Hard on HIGH/CRITICAL |
| Trivy image | Builds both images, scans OS packages and jars, writes an image SBOM | Report-only (`TRIVY_IMAGE_EXIT_CODE=1` to gate) |
| Semgrep | Project rules in `semgrep-rules/` (the dashboard and CI scripts) plus registry packs | Project rules hard (0 findings); registry packs report-only |
| CodeQL (GitHub only) | Python and Java; on for public repos or `ENABLE_CODEQL=true` | Alerts in the Security tab |
| SBOM | CycloneDX from the existing `sbom` profile, uploaded as `bom.json` / `bom.xml`, then scanned by Trivy | Generation hard; scan report-only |
| PostgreSQL integration test | `PostgresDbClientIT` runs `DbClient` against a real PostgreSQL (Testcontainers) | Hard where Docker exists; skips with a message where it does not |

## Run locally

```bash
bash Scripts/supply-chain-scan.sh hadolint
bash Scripts/supply-chain-scan.sh trivy-config
bash Scripts/supply-chain-scan.sh semgrep                    # SEMGREP_REGISTRY=false to stay offline
bash Scripts/supply-chain-scan.sh trivy-image Dockerfile selenium-framework:scan
./mvnw -B -Psbom cyclonedx:makeBom@cyclonedx-bom             # target/bom.json, target/bom.xml
bash Scripts/supply-chain-scan.sh trivy-sbom target/bom.json
./mvnw -B validate -Penforce-strict                          # see what strict mode would fail on
./mvnw -B test-compile failsafe:integration-test@db-it failsafe:verify@db-it -Pdb-integration
semgrep --test semgrep-rules                                 # prove the Semgrep rules still work
```

Trivy and Hadolint are downloaded into `.security-tools/` at the version pinned in the script and checked
against a SHA-256 stored there; a mismatch aborts. To bump a version, change the version and hash together.
Linux x86_64 only; elsewhere install the tool yourself and set `SECURITY_USE_PATH_TOOLS=true`.

## Making the report-only checks gate

Set CI/CD variables (GitLab) or repository variables (GitHub): `TRIVY_IMAGE_EXIT_CODE=1`,
`TRIVY_SBOM_EXIT_CODE=1`. Remove `allow_failure: true` from the matching GitLab jobs. Expect the framework image
to report findings first: its base image (`maven:3.9.6-eclipse-temurin-17`) is old. Bump it, and consider pinning
by digest; that is also what the ignored Hadolint rule DL3008 depends on.

## Enforcer strict mode

`enforce-dependency-tree` only warns, because the full dependency tree could not be checked when it was added.
Run `./mvnw -B validate -Penforce-strict`, fix or exclude what it lists (add exclusions on the dependency that
brings a second logging backend, add a `dependencyManagement` pin for a diverging version), then move the
`<level>ERROR</level>` settings of the `enforce-strict` profile into the main execution.

The convergence conflicts found by the first strict run (17 artifacts: Selenium, WebDriverManager, Guava, Gson,
Jackson, SLF4J, Log4j API, Commons, HttpClient 5, SnakeYAML, JNA, JSpecify and others) are already pinned in the
`dependencyManagement` block of `pom.xml`, under "Dependency convergence pins". When a library upgrade brings a
new conflict, raise the matching pin there instead of adding an exclusion.

## PostgreSQL integration test

`PostgresDbClientIT` is excluded from the default compile and needs the `db-integration` profile (it brings
Testcontainers). CI passes `-Ddb.it.requireDocker=true` so a missing Docker is a failure where it should exist.
Do not combine the profile with `-Pperf`. If your Docker Engine is version 29 or newer and the test cannot reach
Docker, check that `testcontainers.version` in `pom.xml` is at least 1.21.4: 1.21.3 fails against Docker Engine 29
(and any daemon with a raised minimum API version) with "client version 1.32 is too old".

It found a real problem: `DbClient`'s read-only guard accepted `SELECT E'\''; DELETE FROM users; --'` and a
`$$` variant, and both deleted rows on PostgreSQL 16. The guard now refuses any statement containing a backslash
or a `$tag$` quote; pass such values as `?` parameters. The real protection is still a database account that only
has SELECT.

## Semgrep rules

`semgrep-rules/dashboard-security.yml` has eight rules for the code that can change this project's behaviour
(shell=True, disabled TLS verification, unsafe deserialization, timing-unsafe secret comparison, weak hashes,
insecure temp files and modes, hard-coded 0.0.0.0, paths built from request data without containment). Each is
covered by a fixture in `dashboard-security.py`; run `semgrep --test semgrep-rules` after editing a rule.
