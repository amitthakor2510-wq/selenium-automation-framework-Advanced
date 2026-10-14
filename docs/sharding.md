<div align="center">

# 🧩 Test Sharding (duration-balanced)

</div>

Pipelines used to split work only by **site × browser**. One slow site therefore set the length of the whole run while other legs sat idle. Sharding splits **one suite** into several smaller suites that run as parallel CI jobs, balanced by how long each test class took in earlier runs.

It is **opt-in**. With the defaults (1 shard) every pipeline runs the original suite file exactly as before.

---

## How it works

```
testng-suites/saucedemo-regression.xml      ← the suite you already have
        │   Scripts/shard-tests.py plan --shards 3 --index K
        ▼
target/shards/saucedemo-regression-shard-K-of-3.xml   ← generated, one per CI job
```

1. The script lists the suite's test classes (from its `<packages>` / `<classes>`).
2. Each class gets a weight: its recorded duration from `testng-suites/shard-durations.json`, or the median of the known ones when it has none yet. With no history at all every class weighs the same, so shards are balanced by class count.
3. Classes are dealt out heaviest-first to whichever shard is currently lightest ("longest processing time first").
4. Each shard suite keeps the original `<groups>`, `<listeners>`, `<parameter>`, `parallel` and `thread-count`, so smoke/regression group filtering behaves exactly as in the full suite. Only the class list shrinks, and the suite name gains ` [shard i/n]`.

The plan is deterministic. Every CI job computes it on its own from the same suite and the same committed history, so shards never overlap and never miss a class.

---

## Turning it on

### GitHub Actions

Pick one (most specific wins):

| Where | Effect |
|---|---|
| `shard.count.<site>=3` in `pipeline-config.properties` | that site only |
| repository Variable `SHARD_COUNT=3` (Settings → Secrets and variables → Actions → Variables) | every site without its own line |
| `shard.count=3` in `pipeline-config.properties` | every site without a variable or own line |

The `matrix-setup` job then emits one `test` leg per shard (`-shard1of3` …). A suite is never split into more shards than it has classes, so asking for 8 on a 3-class suite gives 3.

### GitLab CI

GitLab matrix values must be static, so two numbers are edited together in `.gitlab-ci.yml`:

```yaml
variables:
  SHARD_TOTAL: "3"                       # ← was "1"

test:
  parallel:
    matrix:
      - SITE: [ demoqa, saucedemo, SAHMAT ]
        BROWSER: [ chrome, firefox, edge ]
        SHARD: [ "1", "2", "3" ]         # ← add this line; keep it in step with SHARD_TOTAL
```

A site with fewer classes than `SHARD_TOTAL` simply skips its extra shards (the job ends cleanly with a message).

### Jenkins

Not sharded. Its per-site parallel stages share one workspace, and wiring shards into it safely needs a real Jenkins run to check.

---

## Keeping the timings fresh

The history file is only as good as its last update. Every test leg records how long each class took, and a report-only `shard-durations` job merges them (moving average, so one slow run does not wreck the plan) and publishes the result as an artifact:

- **GitHub:** download the `shard-durations` artifact of a run.
- **GitLab:** download the `shard-durations` job's artifact.

Commit that file as `testng-suites/shard-durations.json`. Nothing is committed automatically, and the job can never fail a pipeline.

Locally, after any normal run:

```bash
python3 Scripts/shard-tests.py learn --suite-key saucedemo-regression
```

History is keyed per suite (`saucedemo-regression`, `SAHMAT-safari-smoke`, …) because a class runs far fewer methods in smoke than in regression.

---

## Commands

```bash
# write shard 2 of 3 and print its path (stdout is only the path)
python3 Scripts/shard-tests.py plan --suite testng-suites/saucedemo-regression.xml --shards 3 --index 2

# write all shards
python3 Scripts/shard-tests.py plan --suite testng-suites/saucedemo-regression.xml --shards 3

# run one locally
mvn test -Dsite=saucedemo -DsuiteXmlFile=target/shards/saucedemo-regression-shard-2-of-3.xml

# how many shards are useful for this suite?
python3 Scripts/shard-tests.py count --suite testng-suites/saucedemo-regression.xml --max 8
```

`plan` prints the expected load of every shard to stderr, so you can see whether the split looks sensible.

---

## Limits

- **A class is the smallest unit.** One huge class cannot be split, so the longest class sets a floor on how fast a shard can finish.
- **Dependencies must stay inside a class.** `dependsOnMethods` within a class is fine; a dependency between two classes could land them in different shards. The suites in this repo have none.
- **Package scanning reads source files** under `src/test/java`: non-abstract public classes that contain `@Test` and do not use JUnit 5.
- **`<suite-files>` is rejected** with an error instead of silently dropping tests.
- **Per-shard reports.** Allure results, Extent reports, self-healing reports and gate markers are written per shard, so they never overwrite each other when artifacts are merged. The Allure report still shows one combined run.
- **Coverage.** JaCoCo files are uploaded per shard and merged by the coverage gate as before.
- **Not run on real CI yet.** The planner has unit tests (`.github/workflows/scripts/test_shard_tests.py`, run by the GitHub Actions `unit-tests` job) and the pipeline logic was checked by simulating the matrix and shell steps, but no real pipeline has executed it.
