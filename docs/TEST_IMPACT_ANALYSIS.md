# Test Impact Analysis (TIA)

Run only the test classes actually affected by a code change, instead of the full suite,
via `git diff` → compiled-class dependency graph → impacted test classes.

Implementation: `src/main/java/com/automation/core/tia/` (entry point: `TiaCli`).
Tests: `src/test/java/com/automation/core/tia/`.

## Quick start

```bash
# See what would run, without running it
./Scripts/test-impact-analysis.sh --base origin/main

# Actually run it
./Scripts/test-impact-analysis.sh --base origin/main --run

# Compare two fixed commits (e.g. reproducing what a specific PR would have run)
./Scripts/test-impact-analysis.sh --base origin/main --head HEAD --run
```

Or drive the analyzer directly via Maven:

```bash
mvn -q compile test-compile
mvn -q exec:java@tia -Ptia -Dtia.base=origin/main
cat target/tia/impact-report.md
```

Both produce `target/tia/`:

| File | What it's for |
|---|---|
| `mode.txt` | `IMPACTED` or `FULL` — what the CI/script wrapper branches on |
| `impact-report.md` | Human-readable summary: changed files, impacted tests, and *why* each one is impacted |
| `impacted-tests.txt` | Flat, sorted FQCN list |
| `impacted-tests-<site>.txt` | Same, comma-separated, one file per site |
| `testng-impacted-<site>.xml` | A generated TestNG suite ready to hand to `-DsuiteXmlFile` |

## Why per-site output

This framework selects its target site through a single `-Dsite=...` JVM system property
(see `ConfigReader`) — one `mvn test` invocation always runs against exactly one site. TIA's
output is grouped the same way (`impacted-tests-demoqa.txt`, `testng-impacted-saucedemo.xml`,
...) so `Scripts/test-impact-analysis.sh --run` can loop `mvn test -Dsite=<site>
-DsuiteXmlFile=target/tia/testng-impacted-<site>.xml` once per affected site instead of trying
to mix sites in one run. A test class that isn't under a known site package (e.g. a plain unit
test under `com.automation.core.data`) is bucketed under `other` and left for a normal
`mvn test` — it's outside this framework's site-driven TestNG suite convention entirely.

## How dependencies are found

1. **`git diff --name-status`** (plus `git ls-files --others --exclude-standard` for brand-new,
   not-yet-committed files, which `git diff` never reports) produces the raw changed-file list.
2. Every changed `.java` file is mapped to its fully-qualified class name from its path under
   `src/main/java/` or `src/test/java/`.
3. **`ClassFileScanner`** reads the raw `.class` file format directly (a small, dependency-free
   constant-pool parser — see its javadoc for exactly which tags it walks) and pulls out every
   `CONSTANT_Utf8` string in each compiled class, grouped by *top-level* class (an inner/anonymous
   class's strings roll up into its owning `.java` file's class — that's the granularity a single
   git-diff line actually changes at).
4. **`DependencyGraph`** treats "class A's constant pool contains class B's name" as "A depends on
   B" — this is deliberately over-inclusive rather than a strict `CONSTANT_Class`-only reader:
   it also catches classes referenced only through a `Signature` attribute (generics), an
   annotation's class-literal element (e.g. `@Test(dataProviderClass = X.class)` — that reference
   is stored as a plain descriptor string, not a `CONSTANT_Class` entry), or a string literal used
   for reflection. A strict reader would silently miss all of those; for test impact analysis, a
   false negative (skipping a test that should have run) is far worse than the rare false
   positive (running one extra test).
5. Given the set of changed classes, `DependencyGraph.reverseTransitiveClosure` walks the graph
   **backwards**: every class that (directly or transitively) depends on a changed class. That
   closure, intersected with the concrete (non-abstract) classes under `src/test/java`, is the
   impacted-test set.

A changed **test-source** class seeds the same closure a changed **main-source** class does —
not just "run this one test". This project's own `BaseTest`, `BaseApiTest`, `KeywordTestBase`,
and `MobileBaseTest` all live under `src/test/java`, not `src/main/java`; a change to any of them
has to fan out to every subclass exactly the way a changed main-source utility class fans out to
its callers. (This was caught as a real bug while building this feature — see
`TestImpactAnalyzerIntegrationTest#baseSourceChangePropagatesToSubclassesEvenWhenBaseIsUnderTestRoot`
for the regression test.)

## Resource files (test data, config, object repository)

A resource file has no bytecode of its own, so it can't show up in `DependencyGraph`. TIA
handles two different patterns:

- **Literal-path resources** — `testdata/**` files are opened by a literal string in test source
  (e.g. `"src/test/resources/testdata/login.yaml"` inside a `@DataProvider` method or
  `KeywordReader` call). `ResourceReferenceIndex` scans every `.java` source file once for string
  literals ending in a resource-like extension and records which classes mention each resource's
  basename. A resource change maps precisely to the specific test class(es) that read it.
- **Constructed-path resources** — `config/<site>.properties` and
  `objectrepository/<site>.properties` are opened by *building* the path at runtime
  (`ConfigReader`'s `"config/" + site + ".properties"`), so there's no literal string to find.
  `SiteMapper.siteFromResourcePath` infers the site from the file's own name/location instead,
  and every test class under that site's package is treated as impacted. This is intentionally
  coarser (whole-site, not whole-test-class-precise) — it's still far narrower than a full run,
  and it's the honest limit of what static analysis can prove here.
- **No literal reference and no inferable site** (e.g. someone adds a new top-level resource
  directory this logic doesn't know about) → falls back to a full-suite run rather than silently
  assuming zero impact.

## What always forces a full run (`UnsafeChangeRules`)

Any changed file matching a pattern in `src/test/resources/tia/unsafe-patterns.txt` (or the
built-in defaults in `UnsafeChangeRules.defaults()` if that file is absent) makes TIA report
`FULL` instead of a narrowed list — deliberately conservative, since a TIA tool that ever
*silently* skips a test a change actually broke is worse than no TIA at all:

- Build/tooling: `pom.xml`, `checkstyle.xml`, `owasp-suppressions.xml`, `Dockerfile`,
  `docker-compose.yml`, `Jenkinsfile`, `.gitlab-ci.yml`
- Suite topology and automation scripts: `testng-suites/**`, `Scripts/**`, `.github/workflows/**`
- Config that applies to every site at once: `config/global.properties`,
  `config/_TEMPLATE.properties.example`, `logging.properties`, `log4j2.xml`, `allure.properties`

Also forced to `FULL`, dynamically, regardless of that file:
- A changed **main-source** file was **deleted** (nothing left to compute dependents of, and the
  rest of the codebase may not even compile against the deletion yet).
- A changed main/test source file's class isn't found in the compiled output — you need to
  `mvn compile test-compile` before running TIA; it won't guess.
- A resource change with no literal reference and no inferable site (see above).

Tune `src/test/resources/tia/unsafe-patterns.txt` (one glob per line, `#` comments allowed) to
add project-specific patterns without recompiling.

## What deliberately can't be traced (known limitations)

- **Reflection with a computed (non-literal) class name.** `SiteRegistry`-style
  `Class.forName(computedName)` where `computedName` isn't a compile-time constant produces no
  matching string anywhere in the bytecode. In this codebase that pattern is avoided in favor of
  ordinary `import`s and static dispatch specifically because of this limitation. See
  "Coverage-based fallback" below for a second signal that closes this gap when it does come up
  — the honest, precise fix (not just "add to unsafe-patterns.txt and take the full-suite hit")
  for exactly this case.
- **Config-driven behavior with no corresponding class change**, beyond what `SiteMapper`'s
  site-based fallback already covers (e.g. a *totally* new resource category under
  `src/test/resources/` that isn't `config/`, `objectrepository/`, `visual-baselines/`, or
  `testdata/`) — falls back to `FULL`, see above. Coverage data doesn't help here: JaCoCo
  instruments bytecode, not file I/O, so it has nothing to say about which test opened which
  resource file.
- **CI infrastructure changes** (a workflow YAML edit, a Docker base image bump) obviously can't
  be reasoned about by looking at Java bytecode — always `FULL` via `unsafe-patterns.txt`.

## Coverage-based fallback

`DependencyGraph`'s bytecode analysis (see above) only ever sees a dependency if some string
matching the target class's name literally appears in the caller's compiled constant pool. A
class reached only through reflection with a name built at runtime — `Class.forName(computed)`,
a dependency-injection lookup, anything where the actual class isn't spelled out anywhere in the
caller's own source — leaves nothing for it to find. `CoverageMap` closes that gap with a second,
independent signal: real, observed runtime behavior from an actual JaCoCo execution, which sees
straight through *how* a class was reached because it doesn't care — it only records that it ran.

### How it works

1. **Capture** (`Scripts/build-coverage-map.sh <site>`) runs that site's regression suite with
   two things turned on that are both off by default (see their property comments in `pom.xml`):
   - `-Djacoco.jmx=true` — the JaCoCo agent registers a JMX MBean
     (`org.jacoco:type=Runtime`) instead of (or alongside) its usual dump-on-exit file.
   - `-Dcoverage.map.enabled=true` — `AlterSuiteForCoverageMapListener`
     (`src/test/java/com/automation/sites/listeners/`, auto-registered via TestNG's
     `ServiceLoader`-based `META-INF/services/org.testng.ITestNGListener`, but a complete no-op
     unless this flag is set) forces the suite to `parallel="none"` and attaches
     `JacocoPerTestCoverageListener`.

   `JacocoPerTestCoverageListener` resets the JaCoCo runtime immediately before each test class
   and dumps-and-resets immediately after, writing one `target/jacoco-per-test/<TestClassFqcn>.exec`
   file per test class.

2. **Build** (`mvn exec:java@coverage-map -Pcoverage-map`, wrapped by the same script) reads every
   one of those files via `CoverageExecReader` (the one class in this project that imports
   `org.jacoco.core` — see its own javadoc and the dependency's comment in `pom.xml`), keeps only
   the classes each file shows at least one hit probe for, and writes the result as plain,
   dependency-free tab-separated text to `target/tia/coverage-map.txt`:
   `testFqcn<TAB>coveredClassFqcn`, one pair per line.

3. **Use** — `com.automation.core.tia.CoverageMap` loads that file (or, absent one, degrades to an
   empty no-op — a normal TIA run is completely unaffected if this was never opted into).
   `TestImpactAnalyzer` unions its results into the impacted set for every changed main/test
   class, alongside (never instead of) `DependencyGraph`'s own reverse-transitive closure.

### Why serial execution is non-negotiable

JaCoCo's runtime execution data is one shared accumulator per JVM. This project's own TestNG
suites run `parallel="classes"` with multiple threads *inside a single forked JVM* (see the
`argLine`/`forkCount` comments in `pom.xml`) — if two test classes ran concurrently during
capture, a reset-then-dump cycle nominally scoped to "test class A" could actually contain
whatever class B happened to touch in that same window. That's not a smaller, still-useful
signal; it's silently wrong data, and wrong data is worse than no data for a tool feeding
test-selection decisions. `AlterSuiteForCoverageMapListener` avoids this by forcing
`parallel="none"` whenever capture is enabled; `JacocoPerTestCoverageListener` also independently
*detects* concurrent class execution as a backstop (in case something else re-parallelizes it)
and, if it ever sees more than one class active at once, writes
`target/jacoco-per-test/UNRELIABLE.marker` and refuses to let `CoverageMapBuilder` produce a map
from that run at all — see both classes' javadoc.

The direct consequence: building/refreshing the map is meaningfully slower than the normal
parallel CI run. That's why it's wired as its own nightly/`workflow_dispatch`-only `coverage-map`
job in `github-ci.yml`, not part of the PR-triggered `test-impact-analysis` job — see that job's
comment for the exact trigger condition.

### Why a stale map is still safe to use

The map reflects whatever commit was checked out during the last capture run — usually not "right
now". That's fine specifically because this data only ever **adds** tests to the impacted set,
never removes any: a stale "test T once observed class C" entry that's no longer true today just
costs one extra test running (safe, if slightly wasteful), and a genuinely new class that didn't
exist when the map was captured simply doesn't appear in it at all — `DependencyGraph` (always
rebuilt fresh from the current checkout) still covers that case on its own, exactly as if no
coverage map existed. Nothing about a stale map can cause TIA to skip a test it otherwise
would've run.

## CI integration

`.github/workflows/github-ci.yml` has a `test-impact-analysis` job (PR-triggered) that computes
the impacted set against the PR's base branch and posts `impact-report.md` to the job summary,
plus uploads `target/tia/` as an artifact. It's **informational only** — it does not gate or
replace the existing full `test` / `mobile-test` matrix, which remains the real safety net on
every push and PR exactly as before.

To actually gate CI on TIA once you've built confidence in it (e.g. running both in parallel for
a few weeks and confirming IMPACTED mode never misses a failure FULL mode would have caught),
the migration is: replace the `test` job's fixed `-DsuiteXmlFile=testng-suites/<site>-regression.xml`
with the generated `target/tia/testng-impacted-<site>.xml`, guarded by `target/tia/mode.txt` ==
`IMPACTED` (fall back to the existing fixed suite file otherwise) — i.e. inline what
`Scripts/test-impact-analysis.sh --run` already does into the workflow's own steps.

## Design constraint: `com.automation.core.tia` stays dependency-free

`com.automation.core.tia` is written against nothing but `java.io` / `java.nio` / `java.util` —
no ASM, no third-party bytecode library, and — despite "Coverage-based fallback" above adding a
real new Maven dependency (`org.jacoco.core`) to this project — still true of every class in this
specific package. That dependency is confined to exactly one class,
`com.automation.core.coverage.CoverageExecReader`, in a separate package; by the time
`com.automation.core.tia.CoverageMap` (or anything else in `tia`) touches coverage data, it's
already been reduced to plain tab-separated text. `TestImpactAnalyzer`'s own bytecode-graph path
still compiles and runs anywhere a JDK does, with or without `org.jacoco.core` resolvable at all
— the coverage signal is additive and optional by construction, not a hard requirement the rest
of TIA now silently depends on. (This sandbox has no Maven Central access, matching a limitation
this project's own `pom.xml` comments already note elsewhere — `CoverageExecReader` and the two
new TestNG listeners that feed it could not be compile-verified here for that reason; the rest of
`com.automation.core.tia`, unaffected by the new dependency, was.)
