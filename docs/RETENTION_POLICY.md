<div align="center">

# 🧹 Video & Report Retention Policy

</div>

---

Video recording (`video.enabled=true`, see [reports-and-quality.md](reports-and-quality.md#video-recording))
and Allure's cross-run trend widgets both write things that nothing ever
automatically deletes. Left alone, three things grow without bound:

1. **`target/videos/` on a developer's own machine** — every local `mvn test`
   run with recording on adds more files; nothing ever cleans up old ones.
2. **The `gh-pages` git branch (GitHub Actions)** — bounded *content* per
   commit, but unbounded *history*: every deploy is a new commit, forever.
3. **GitLab CI/CD artifact storage** — the `pages` job's artifact had no
   expiry, so every pipeline run left behind its own full copy of `public/`.

This doc covers what each of those actually is, why it grows, and the prune
step now in place for each.

## 📋 Table of Contents
- [🎥 Local `target/videos/` pruning](#-local-targetvideos-pruning)
- [🐙 GitHub: `gh-pages` branch history](#-github-gh-pages-branch-history)
- [🦊 GitLab: Pages artifact expiry](#-gitlab-pages-artifact-expiry)
- [🏗️ Jenkins](#️-jenkins-already-covered)
- [❓ FAQ](#-faq)

---

## 🎥 Local `target/videos/` pruning

`VideoRecorder` writes one file per test attempt, flat in `target/videos/`,
named `<test>_<timestamp>_<random>.avi` — there's no per-run subfolder, so
nothing on disk marks where one `mvn test` invocation ended and the next
began. Over weeks of local debugging with `-Dvideo.enabled=true`, this
directory only ever grows.

`com.automation.core.retention.ArtifactRetentionCleaner` fixes this by
reconstructing "runs" from file timestamps: any two files less than
`--gap-minutes` (default 30) apart belong to the same run; a bigger gap
starts a new one. It then keeps the newest `--keep-runs` (default 5) runs
and deletes the rest.

**Usage:**
```bash
./Scripts/prune-artifacts.sh                       # keep last 5 runs of target/videos
./Scripts/prune-artifacts.sh --dry-run              # show what would be deleted, delete nothing
./Scripts/prune-artifacts.sh --keep-runs 10         # keep more history
```

Or directly via Maven (what the script above wraps):
```bash
mvn -q exec:java@prune-artifacts -Pprune-artifacts \
  -Dprune.keepRuns=10 -Dprune.dryRun=true
```

This is **opt-in only** — it is not bound to any lifecycle phase, so an
ordinary `mvn test` or `mvn clean` never deletes anything on its own. Run it
yourself whenever `target/videos/` is taking up more space than you want.

This is a heuristic, not a guarantee: a run that pauses on one slow test for
longer than `--gap-minutes` could get split into two "runs" and have its
older half pruned a little sooner than expected. Since this only ever
deletes local, disposable recordings (never anything CI or git depends on),
that's a safe direction to be wrong in — worst case you re-record a video
you already had.

## 🐙 GitHub: `gh-pages` branch history

The GitHub Actions pipeline's "Deploy Reports to GitHub Pages" step
(`peaceiris/actions-gh-pages`, `keep_files: true`) commits fresh report
output to `gh-pages` on every push/PR run. Two things already bound the
*content* of any one commit:
- `simple-elf/allure-report-action`'s `keep_reports: 20` caps the Allure
  trend data.
- Videos are deliberately **not** copied into `gh-pages-publish/` (see the
  comment above that step in `github-ci.yml`) — they get their own
  short-lived (`retention-days: 7`) workflow artifact instead.

Neither of those bounds the branch's **git history**, though — every deploy
is a new commit, and most of the report's data files differ commit to
commit, so every one of those commits' blobs stays in the branch's object
database forever. That's what eventually turns a repo with a small *current*
`gh-pages` tree into a multi-GB `.git`.

`.github/workflows/gh-pages-retention.yml` handles this separately from the
main pipeline: it runs weekly (and on manual dispatch), checks out
`gh-pages`, and — only once the branch has more than `KEEP_COMMITS` (default
50) commits — rewrites its history down to a single fresh commit with the
exact same tree GitHub Pages is already serving, then force-pushes it.
Nothing served by Pages changes; only the discarded history and its
now-unreachable blobs are dropped.

```bash
# Manually, via GitHub's "Run workflow" button:
#   gh-pages Retention (squash history) → Run workflow → keep_commits: 50
```

## 🦊 GitLab: Pages artifact expiry

GitLab Pages only ever **serves** the latest deployment — unlike `gh-pages`,
there's no browsable git history behind it. But the *job artifact* backing
each deployment (a full copy of `public/`, videos included) is an ordinary
GitLab CI/CD artifact, and every other job in `.gitlab-ci.yml` already sets
an explicit `expire_in` — the `pages` job was the one exception. Without it,
that artifact defaulted to the project's own "keep artifacts" setting
(commonly "forever" on self-hosted instances), so every pipeline run left
behind its own full copy in artifact storage even though only the newest one
was ever actually served.

Fixed with one line:
```yaml
pages:
  artifacts:
    expire_in: 30 days   # was previously unset
    paths:
      - public/
```

As a second, smaller safeguard, the `report` job now prunes `target/videos/`
(keeping the newest 10 runs, via the same `ArtifactRetentionCleaner` used
locally) immediately before mirroring it into `public/videos/` — so leaving
`RECORD_VIDEO` on for a long regression run with many failures can't balloon
a single deployment's size the way it has in the past (see the "413 Request
Entity Too Large" history noted elsewhere in `.gitlab-ci.yml`). This step is
best-effort (`|| true`) and never fails the `report` job.

## 🏗️ Jenkins (already covered)

Jenkins needed no changes here: `options { buildDiscarder(logRotator(numToKeepStr: '10')) }`
already caps both the archived `target/videos/**` artifacts and the Allure
plugin's own per-build history directory to the last 10 builds — Jenkins
purges the rest of each discarded build's files itself.

## ❓ FAQ

**Will squashing `gh-pages` break the live Pages URL?**
No — the squash commit's tree is byte-for-byte what's already checked out
and being served; only the branch's *history* changes.

**Do I need to do anything to a local clone of `gh-pages` after a squash?**
Only if you have one and want the disk space back locally too — re-clone it,
or run `git gc --aggressive --prune=now` after fetching. The GitHub-hosted
copy reclaims space on its own.

**Does the local video pruner ever run automatically?**
No. It's deliberately opt-in (see above) — run it manually or wire it into
your own cron/hook if you want it scheduled.
