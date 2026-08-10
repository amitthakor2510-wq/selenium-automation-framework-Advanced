// =============================================================
// Jenkins CI/CD pipeline - Selenium + Java + TestNG + Maven
// Requires:
//   - JDK 17            (Tools > JDK, name: "JDK17")
//   - Maven             (Tools > Maven, name: "Maven3")
//   - Allure Tool       (Tools > Allure Commandline, name: "allure")
//   - HTML Publisher    (Manage Jenkins > Plugins > HTML Publisher)
// =============================================================

pipeline {

    agent any

    tools {
        jdk 'JDK17'
        maven 'Maven3'  // Matches your global configuration name exactly
        allure 'allure'             // Injects the configured allure command-line runner
    }

    parameters {
        choice(
                name: 'SUITE_TYPE',
                choices: ['regression', 'smoke'],
                description: 'Which suite to run for each discovered site'
        )
        string(
                name: 'SITE',
                defaultValue: 'ALL',
                description: 'Site to test, e.g. "demoqa". Use "ALL" to run every discovered site'
        )
        choice(
                name: 'BROWSER',
                choices: ['chrome', 'firefox', 'edge'],
                description: 'Browser to run against (ignored per-site if ALL_BROWSERS is checked)'
        )
        booleanParam(
                name: 'ALL_BROWSERS',
                defaultValue: false,
                description: 'Run every discovered browser site against chrome, firefox, AND edge in parallel (ignores BROWSER above). Off by default to keep a normal build\'s branch count/runtime unchanged.'
        )
        booleanParam(
                name: 'HEADLESS',
                defaultValue: true,
                description: 'Run browser headless (recommended for CI)'
        )

        string(
                name: 'RETRY_COUNT',
                defaultValue: '0',
                description: 'Number of retries for failed tests. 0 = disabled for CI speed.'
        )

        string(
                name: 'SECURITY_FAIL_CVSS',
                defaultValue: '11',
                description: 'OWASP Dependency-Check: fail the Security Scan stage on any dependency with a CVSS score >= this value. 11 = never fails (report-only). 7 is a common "fail on High/Critical" cutoff — see the "security" profile comment in pom.xml.'
        )
    }

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        // A second build of the same job (e.g. two pushes to the same branch
        // in quick succession) aborts whichever earlier run is still queued/
        // executing instead of both running to completion — the first run's
        // result is about to be superseded anyway, so there's no reason to
        // spend agent time finishing it. GitHub Actions gets the equivalent
        // via `concurrency:`, GitLab CI via `interruptible: true`.
        disableConcurrentBuilds()
        // Without this, `agent any` makes Jenkins perform an implicit,
        // unconfigurable full checkout ("Declarative: Checkout SCM", using
        // whatever timeout/clone settings happen to be set in the job's own
        // UI config, or Jenkins' 10-minute default if none are) BEFORE any
        // stage below — including the stage('Checkout') a few lines down,
        // which then does the exact same full `checkout scm` a second time.
        // On a build where the agent's link to GitHub is slow/unstable
        // (this repo is ~27k objects), that implicit checkout can eat the
        // full 10-minute default timeout and get killed mid-transfer:
        //   ERROR: Timeout after 10 minutes
        //   fatal: fetch-pack: invalid index-pack output
        // — and because it fails before stage('Checkout') is even reached,
        // there's no Jenkinsfile-level lever to give it a longer timeout or
        // a shallower clone; that config only lives in Jenkins' job UI, not
        // in this file. skipDefaultCheckout() removes that implicit
        // checkout entirely, leaving stage('Checkout') below as the ONE
        // checkout per build — which this file (and CloneOption extensions
        // added there) fully controls.
        skipDefaultCheckout()
    }

    triggers {
        // Accessibility (axe-core) and visual-regression (AShot screenshot
        // diffing) are opt-in by nature — both are slower and more
        // flake-prone than the standard functional suites, and only exist
        // for demoqa — so they don't run on every commit like the regular
        // SUITE_TYPE choice does. Instead they run once a night regardless
        // of what SUITE_TYPE this build's parameters otherwise specify; see
        // the "Nightly Extra Coverage" stage below, gated on this same
        // TimerTrigger cause.
        cron('H 2 * * *')
    }

    stages {

        stage('Acquire Shared-Box Lock') {
            // ROOT CAUSE (2026-08-05 build, diagnosed from console log): this
            // agent runs with more than one executor, and disableConcurrentBuilds()
            // above only blocks two runs of THIS SAME job — it does nothing to
            // stop a *different* job (a dependabot/PR-triggered build, a manual
            // build with a different SITE param, the nightly cron build
            // overlapping a push-triggered one, etc.) from starting on this same
            // physical box while a build is already running. When that happens,
            // two builds' "Run Tests Per Site" stages both launch up to 6
            // concurrent ChromeDriver processes each — 12+ total, not the 6 the
            // 8-attempt port-race retry budget in DriverFactory was sized for —
            // so ChromeDriver setUp() fails across nearly every test class
            // instead of the occasional one. The same collision hits Mobile
            // Test: both builds try to bind emulator port 5554; the loser's
            // emulator silently shifts to 5556 (visible in that build's own
            // "adb reconnect offline" step reconnecting BOTH emulator-5554 and
            // emulator-5556), and the resulting CPU/RAM contention from a whole
            // second build's worth of Chrome/JVM/Maven processes is enough to
            // stall the second emulator's adbd handshake past the 120s timeout
            // even though Android itself finished booting internally.
            //
            // Fix: a simple mkdir-based mutex (atomic, no plugin dependency) so
            // any two builds of ANY job that reach this Jenkinsfile on this box
            // queue up instead of overlapping. Stale-lock protection: if the PID
            // recorded by the lock holder no longer exists (e.g. that build's
            // agent died hard, same failure mode the "Cleanup (Stale Processes)"
            // stage below already guards against), the lock is treated as
            // abandoned and reclaimed rather than blocking forever.
            steps {
                sh '''
                    LOCK_DIR="/tmp/selenium-framework-pipeline.lockdir"
                    ACQUIRED=0
                    for i in $(seq 1 360); do
                        if mkdir "$LOCK_DIR" 2>/dev/null; then
                            echo "$$" > "$LOCK_DIR/pid"
                            echo "${BUILD_TAG:-unknown}" > "$LOCK_DIR/build"
                            ACQUIRED=1
                            break
                        fi
                        HOLDER_PID="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
                        if [ -n "$HOLDER_PID" ] && ! kill -0 "$HOLDER_PID" 2>/dev/null; then
                            echo "Stale shared-box lock detected (holder process $HOLDER_PID is gone) — reclaiming."
                            rm -rf "$LOCK_DIR"
                            continue
                        fi
                        if [ "$i" = "1" ]; then
                            echo "Another build ($(cat "$LOCK_DIR/build" 2>/dev/null || echo unknown)) holds the shared-box lock — waiting (up to 30 min)..."
                        fi
                        sleep 5
                    done
                    if [ "$ACQUIRED" != "1" ]; then
                        echo "ERROR: could not acquire the shared-box lock within 30 minutes."
                        exit 1
                    fi
                    echo "Shared-box lock acquired."
                '''
            }
        }

        stage('Cleanup (Stale Processes)') {
            // Runs right after the shared-box lock is acquired, before
            // checkout, so a crashed previous build never
            // leaves an orphaned emulator/Appium/ADB process holding CPU,
            // RAM, or a device lock that this build then contends with or
            // silently reuses. Safe to run even when nothing is stale —
            // every command below is best-effort (|| true), so a clean
            // agent just no-ops through this stage in well under a second.
            //
            // NOTE on the Jenkins Process Tree Killer: this pipeline never
            // sets JENKINS_NODE_COOKIE=dontKillMe and never wraps background
            // processes in setsid/nohup+disown — so Jenkins' own launcher
            // still owns every process this build starts (see the Mobile
            // Test stage's `&`-backgrounded emulator/appium) and will reap
            // the whole process tree automatically if the build is aborted
            // or the agent disconnects mid-run. This Cleanup stage is a
            // second, independent safety net for the case that actually
            // bit us before: a PREVIOUS build's processes surviving because
            // that build's agent/JVM died hard enough that even the tree
            // killer never got to run its cleanup.
            steps {
                sh '''
                    echo "Killing any orphaned node/adb/qemu/chrome processes from a previous crashed run..."
                    killall -9 node 2>/dev/null || true
                    killall -9 adb 2>/dev/null || true
                    killall -9 qemu-system-x86_64 2>/dev/null || true
                    # The free-port race itself never leaks a process (the
                    # exception fires before ChromeDriver spawns anything), but
                    # a build that gets hard-killed mid-test (agent disconnect,
                    # OOM-kill, manual abort) can still leave chrome/chromedriver
                    # running and holding a port for the next build. Now that
                    # "Acquire Shared-Box Lock" above guarantees this is the only
                    # build running on the box at this point, it's always safe to
                    # kill these here.
                    killall -9 chromedriver 2>/dev/null || true
                    killall -9 chrome 2>/dev/null || true

                    echo "Removing stale AVD/adb lock files..."
                    find "${HOME}/.android" -name "*.lock" -delete 2>/dev/null || true
                    find /tmp -maxdepth 1 -name "*.lock" -delete 2>/dev/null || true

                    echo "Cleanup complete."
                '''
            }
        }

        stage('Restore Self-Healing Cache') {
            // cleanWs() in post{} wipes the ENTIRE workspace after every
            // build (deliberately — see the comment there), which means
            // self-healing-data/locator-repository.json (moved out of
            // target/ specifically so `mvn clean` wouldn't eat it — see
            // LocatorRepository.java) still wouldn't survive to the next
            // Jenkins build without this: the post{} block's "Cache
            // Self-Healing Repository" step copies it out to
            // $JENKINS_HOME (which cleanWs never touches) right before
            // cleanWs runs, and this step copies it back in before
            // `checkout scm` so this build's very first locator failure
            // has a real cross-run baseline to heal against, same as a
            // local dev machine that never wipes its workspace gets for
            // free.
            steps {
                sh '''
                    CACHE_DIR="${JENKINS_HOME}/selfhealing-cache/${JOB_NAME}"
                    if [ -f "$CACHE_DIR/locator-repository.json" ]; then
                        mkdir -p self-healing-data
                        cp "$CACHE_DIR/locator-repository.json" self-healing-data/locator-repository.json
                        echo "Restored self-healing locator baseline from $CACHE_DIR"
                    else
                        echo "No cached self-healing locator baseline yet — this run starts cold (expected on the first run, or after the cache dir is cleared)."
                    fi
                '''
            }
        }

        stage('Checkout') {
            steps {
                // Explicit CloneOption extensions (not bare `checkout scm`)
                // so this — now the only checkout per build, since
                // skipDefaultCheckout() above removed the redundant
                // implicit one — is resilient to the slow/unstable link to
                // GitHub this agent has shown ("Receiving objects" repeatedly
                // crawling to 30-90 KiB/s mid-fetch in past runs):
                //   - timeout: 30 (vs. Jenkins' 10-minute default) gives a
                //     degraded connection real room to finish rather than
                //     getting SIGTERM'd mid-transfer with "fatal: early EOF".
                //   - shallow/depth: 1 means only the tip commit needs to
                //     transfer, not this repo's full ~27,349-object history —
                //     directly shrinking the amount of data that slow link
                //     has to move, which helps regardless of the timeout.
                // scm.branches / scm.userRemoteConfigs reuse this job's own
                // configured repo URL/credentials/branch rather than
                // hardcoding them here.
                checkout([
                        $class           : 'GitSCM',
                        branches         : scm.branches,
                        userRemoteConfigs: scm.userRemoteConfigs,
                        extensions       : [
                                [$class: 'CloneOption', timeout: 30, shallow: true, depth: 1, noTags: false]
                        ]
                ])
            }
        }

        stage('Build') {
            steps {
                sh 'mvn -B -ntp clean compile test-compile'
            }
        }

        stage('Checkstyle') {
            // Runs right after Build (no browser/emulator dependency, just
            // the source tree) and marks the build UNSTABLE rather than
            // failing it outright — same pattern already used for a failed
            // browser site below, so one style violation doesn't block
            // seeing whether the suite itself still passes. Invokes the
            // named execution directly (see pom.xml's checkstyle-check,
            // bound to the verify phase) instead of running `mvn verify`,
            // which would re-run the entire test suite a second time just
            // to reach that phase.
            steps {
                script {
                    int exitCode = sh(
                            script: 'mvn -B -ntp checkstyle:check@checkstyle-check',
                            returnStatus: true
                    )
                    if (exitCode != 0) {
                        currentBuild.result = 'UNSTABLE'
                        echo 'Checkstyle found violations — see console output above.'
                    }
                }
            }
        }

        stage('Secret Scan') {
            // Runs right after Checkstyle — fast (no CVE database to
            // build, unlike the nightly-only Security Scan stage below),
            // so it can run on every build rather than being cron-gated.
            // Marks the build UNSTABLE rather than failing it outright:
            // a repo's first-ever gitleaks run commonly turns up
            // pre-existing/false-positive matches in history or test
            // fixtures that need triage before this can safely hard-fail
            // builds — switch the UNSTABLE below to `error(...)` once
            // that initial pass is clean (or a .gitleaksignore baseline
            // is in place).
            steps {
                script {
                    sh '''
                        if ! command -v gitleaks &> /dev/null; then
                            echo "gitleaks not found - installing..."
                            GITLEAKS_VERSION="8.18.4"
                            if ! wget -q -O gitleaks.tar.gz "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"; then
                                echo "ERROR: failed to download gitleaks (check agent internet access / DNS / proxy)."
                                exit 1
                            fi
                            mkdir -p .gitleaks-bin
                            tar -xzf gitleaks.tar.gz -C .gitleaks-bin gitleaks
                        fi
                    '''
                    int exitCode = sh(
                            script: 'PATH="$PWD/.gitleaks-bin:$PATH" gitleaks detect --source . --report-format json --report-path gitleaks-report.json --redact',
                            returnStatus: true
                    )
                    if (exitCode != 0) {
                        currentBuild.result = 'UNSTABLE'
                        echo 'gitleaks found potential secrets — see the gitleaks-report.json artifact.'
                    }
                    archiveArtifacts artifacts: 'gitleaks-report.json', allowEmptyArchive: true
                }
            }
        }

        stage('Discover Site Projects') {
            steps {
                script {
                    def suiteFiles = sh(
                            script: "ls testng-suites/*-${params.SUITE_TYPE}.xml 2>/dev/null || true",
                            returnStdout: true
                    ).trim()

                    if (!suiteFiles) {
                        error "No testng-suites/*-${params.SUITE_TYPE}.xml files found."
                    }

                    def allSites = suiteFiles.split('\n').collect { path ->
                        def fileName = path.tokenize('/').last()
                        fileName.replace("-${params.SUITE_TYPE}.xml", '')
                    }
                    // testng-suites/ still has *-safari-<suite>.xml files (kept for
                    // the GitHub Actions pipeline's Safari job) which also end in
                    // "-${params.SUITE_TYPE}.xml", so the glob/collect above picks
                    // them up too — producing bogus pseudo-sites like "demoqa-safari"
                    // that don't correspond to any real config/<site>.properties file.
                    // Jenkins no longer runs Safari at all, so strip these out
                    // rather than let SiteRegistry.validate() reject them and fail
                    // the whole "Run Tests Per Site" stage.
                    allSites = allSites.findAll { !it.endsWith('-safari') }

                    // "mobile" is excluded here and run as its own dedicated
                    // stage below instead. Unlike every other discovered site,
                    // it doesn't use the browser DriverFactory (-Dbrowser/
                    // -Dheadless are meaningless for it) and needs a
                    // completely different toolchain first — Android SDK,
                    // an emulator, and an Appium server — none of which this
                    // pipeline had set up. Running it through this generic
                    // per-site loop meant every mobile run failed outright
                    // with SessionNotCreatedException/ConnectException:
                    // AppiumDriverFactory had nothing listening on
                    // 127.0.0.1:4723 and no booted device to talk to.
                    env.RUN_MOBILE = allSites.contains('mobile') ? 'true' : 'false'
                    def browserSites = allSites.findAll { it != 'mobile' }

                    env.SITES_TO_RUN = (params.SITE == 'ALL')
                            ? browserSites.join(',')
                            : (params.SITE == 'mobile' ? '' : params.SITE)

                    if (params.SITE != 'ALL' && params.SITE != 'mobile') {
                        env.RUN_MOBILE = 'false'
                    }

                    echo "Sites discovered: ${allSites.join(', ')}"
                    echo "Browser sites this run: ${env.SITES_TO_RUN}"
                    echo "Mobile this run: ${env.RUN_MOBILE}"
                }
            }
        }

        stage('Run Tests Per Site') {
            when {
                expression { env.SITES_TO_RUN?.trim() }
            }
            steps {
                script {
                    def sites = env.SITES_TO_RUN.split(',')
                    // Off by default (ALL_BROWSERS=false) so a normal build's
                    // branch count and runtime are unchanged — ticking it
                    // fans each site out across all three browsers instead
                    // of just params.BROWSER, the same coverage the GitHub
                    // Actions matrix now runs on every push/PR by default.
                    def browsers = params.ALL_BROWSERS ? ['chrome', 'firefox', 'edge'] : [params.BROWSER]
                    def testResults = [:]

                    sh 'mkdir -p target/jacoco-artifacts'

                    // Each branch below runs `mvn test` as its own separate
                    // JVM, so a shared/global Maven local repo is safe to
                    // read from concurrently. The only genuinely shared
                    // OUTPUT that two branches running at once would
                    // otherwise collide on is target/allure-results/
                    // environment.properties (AllureEnvironmentWriter writes
                    // it once per JVM) and the default target/jacoco.exec
                    // path (JaCoCo's prepare-agent writes there unless
                    // overridden) — every branch here is a distinct
                    // `sh` step sharing one `agent any` workspace, not an
                    // isolated node the way GitHub Actions/GitLab's matrix
                    // jobs get, so both are explicitly redirected to a
                    // per-branch path below instead of relying on defaults.
                    def branches = [:]
                    sites.each { site ->
                        browsers.each { browser ->
                            def key = browsers.size() > 1 ? "${site}-${browser}" : site
                            branches[key] = {
                                def suiteFile = "testng-suites/${site}-${params.SUITE_TYPE}.xml"
                                echo "==== Running ${params.SUITE_TYPE} for site: ${site}, browser: ${browser} ===="

                                int exitCode = sh(
                                        script: """
                                       mvn -B -ntp test \\
                                          -Dsite=${site} \\
                                          -DsuiteXmlFile=${suiteFile} \\
                                          -Dbrowser=${browser} \\
                                          -Dheadless=${params.HEADLESS} \\
                                          -Dhuman.pause.enabled=false \\
                                          -Dretry.count=${params.RETRY_COUNT} \\
                                          -Dallure.results.directory=target/allure-results/${key} \\
                                          -Djacoco.destFile=target/jacoco-artifacts/${key}.exec \\
                                          -Dsurefire.reportsDirectory=target/surefire-reports/${key} \\
                                          -Dmaven.test.failure.ignore=true
                                    """,
                                        returnStatus: true
                                )
                                testResults[key] = exitCode
                            }
                        }
                    }
                    parallel branches

                    def failedSites = testResults.findAll { k, v -> v != 0 }.keySet()
                    if (failedSites) {
                        currentBuild.result = 'UNSTABLE'
                        echo "Sites with failures: ${failedSites.join(', ')}"
                    }
                }
            }
        }

        stage('Mobile Test') {
            when {
                expression { env.RUN_MOBILE == 'true' }
            }
            environment {
                ANDROID_SDK_ROOT = "${WORKSPACE}/.android-sdk"
                ANDROID_AVD_NAME = 'jenkins_avd'
                ANDROID_AVD_HOME = "${WORKSPACE}/.android-sdk/avd"
                // This box also runs Amit's own persistent Genymotion device
                // (serial 127.0.0.1:6562 — see mobile/README.md) connected to
                // the same adb server around the clock, independent of any
                // Jenkins build. Once this stage's own AVD boots alongside
                // it, adb has *two* devices registered and every bare adb
                // call (wait-for-device, shell, reconnect) errors out with
                // "more than one device/emulator" — that's what actually
                // failed this build, not a stale/leftover process (the
                // Cleanup stage already handles that case). Pinning the CI
                // emulator to a fixed, known port makes its serial
                // deterministic so every adb/Appium call in this stage can
                // target it explicitly and leave the Genymotion device
                // alone. ANDROID_SERIAL is adb's own env var for "use this
                // serial when none is passed via -s" — exporting it here
                // means every `sh` step below picks it up automatically.
                ANDROID_SERIAL = 'emulator-5554'
                // Local hardware boots the AVD slower than a dedicated cloud
                // CI runner, especially with Jenkins/GitLab background load
                // competing for the same CPU/IO at the same time — the
                // default 5s adb server timeout is tuned for the latter, not
                // this box, and causes spurious "device offline"/timeout
                // errors under contention. Exported here so every `sh` step
                // in this stage (adb wait-for-device, appium's own adb
                // calls, etc.) picks it up automatically.
                ANDROID_ADB_SERVER_TIMEOUT = '120'
            }
            steps {
                // Every other stage in this pipeline (Run Tests Per Site,
                // Performance Smoke) captures its own exit code and marks
                // the BUILD unstable instead of letting a failure propagate
                // — this stage is the one exception, and it's what actually
                // fails builds like this one. The emulator setup script
                // below still has `set -e` and an explicit `exit 1` on a
                // stuck/offline emulator (e.g. no real KVM acceleration on
                // this agent — a slow/flaky boot, not a code regression),
                // so without catchError() here that `sh` step throws,
                // fails this stage, and — because a failed (not unstable)
                // stage stops the pipeline — skips the unrelated Nightly
                // Extra Coverage and Performance Smoke stages too. Wrapping
                // in catchError makes a bad mobile-emulator boot behave
                // exactly like a failed browser test: mark UNSTABLE, keep
                // going, still report accurately in post{}.
                catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                    script {
                        // Same "install if missing" pattern as GitHub
                        // Actions/GitLab CI use for their mobile jobs, so a
                        // node that already has these cached only downloads
                        // on a cold cache.
                        //
                        // ROOT CAUSE (2026-08-10 build): a bare "does /dev/kvm
                        // exist" check isn't enough — this agent HAS /dev/kvm,
                        // so the emulator's default "-accel auto" picked
                        // hardware acceleration, but the underlying host/
                        // hypervisor doesn't pass through full CPU
                        // virtualization features to the guest (confirmed in
                        // that build's emulator.log: "host doesn't support
                        // requested feature: CPUID.01H:ECX.aes", "Not all
                        // modern X86 virtualization features supported...
                        // Setting AVD to run with 1 vCPU core only"). That
                        // produces a half-broken state, not a clean fallback:
                        // the guest's own boot sequence completes internally
                        // ("Boot completed in NNNN ms" in emulator.log) but
                        // AES-NI-dependent late-boot work (Android's keystore/
                        // crypto init) never finishes, so adbd never comes
                        // fully online — every `adb shell` call reports
                        // "device offline" forever, and the build eventually
                        // times out. Explicit software mode (-accel off) is
                        // slower but doesn't hit that AES-NI gap, so it's the
                        // more reliable choice on hosts that fail this check
                        // — flip ACCEL_MODE (and the matching timeout below)
                        // to "on" once this agent has real nested-virt
                        // passthrough with AES-NI exposed to the guest.
                        sh '''
                        set -e
                        ACCEL_MODE="off"
                        if [ -e /dev/kvm ] && [ -r /dev/kvm ] && [ -w /dev/kvm ] \\
                           && grep -qE '^flags\\s*:.*\\baes\\b' /proc/cpuinfo 2>/dev/null \\
                           && grep -qE '^flags\\s*:.*\\b(vmx|svm)\\b' /proc/cpuinfo 2>/dev/null; then
                            ACCEL_MODE="on"
                            echo "KVM + required CPU virtualization/AES-NI flags detected — using hardware acceleration."
                        else
                            echo "WARNING: this agent can't offer full hardware acceleration (missing /dev/kvm access, or the host isn't passing through vmx/svm/aes CPU flags to the guest). Forcing '-accel off' (software mode) instead of leaving it on 'auto' — auto has been landing in a half-broken state here where the guest boots internally but adbd never comes online. Software mode is slower but avoids that failure signature; see the Coverage Gate/wait-for-device timeout below, which is widened accordingly."
                        fi

                        if [ ! -d "$ANDROID_SDK_ROOT/cmdline-tools/latest" ]; then
                            echo "Android SDK not found - installing cmdline-tools..."
                            mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
                            wget -q -O cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
                            unzip -q -o cmdline-tools.zip -d "$ANDROID_SDK_ROOT/cmdline-tools"
                            mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
                            rm cmdline-tools.zip
                        fi

                        export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"
                        yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses > /dev/null 2>&1 || true
                        sdkmanager --sdk_root="$ANDROID_SDK_ROOT" "platform-tools" "emulator" "system-images;android-30;default;x86_64" "platforms;android-30" > /dev/null

                        mkdir -p "$ANDROID_AVD_HOME"
                        if [ ! -d "$ANDROID_AVD_HOME/${ANDROID_AVD_NAME}.avd" ]; then
                            echo "no" | avdmanager create avd -n "$ANDROID_AVD_NAME" -k "system-images;android-30;default;x86_64" --force
                        fi

                        if ! command -v node > /dev/null 2>&1; then
                            echo "ERROR: Node.js not found on this agent — required for Appium. Install Node.js on the Jenkins agent, then re-run."
                            exit 1
                        fi
                        if ! command -v appium > /dev/null 2>&1; then
                            npm install -g appium
                        fi
                        # Checked independently of the appium binary above —
                        # same reasoning as the GitLab CI fix: this agent may
                        # persist across builds, so appium being present
                        # doesn't guarantee the uiautomator2 driver is.
                        if ! appium driver list --installed 2>&1 | grep -qi uiautomator2; then
                            appium driver install uiautomator2
                        fi

                        # -9 (not a bare pkill/SIGTERM): a hung emulator from a
                        # crashed previous run can outlive a plain SIGTERM, and
                        # if it's still squatting on port 5554 the emulator we
                        # launch below silently falls back to the next free
                        # port pair (5556/5557) instead of 5554/5555 — while
                        # ANDROID_SERIAL stays pinned to "emulator-5554", so
                        # every adb call in this script then targets the dead
                        # leftover process, not the one we just started. This
                        # is consistent with the "Cleanup (Stale Processes)"
                        # stage's own killall -9 for the same class of process.
                        # ROOT CAUSE (2026-08-10 build): the pipeline-start
                        # "Cleanup (Stale Processes)" stage only runs ONCE,
                        # at the very beginning of a build — it can't clean up
                        # anything that starts an emulator LATER, whether from
                        # this same build (a Jenkins controller restart mid-
                        # build, exactly what that 2026-08-10 log showed: a
                        # 3-day gap between pipeline stages) or from a
                        # completely different build/job that ran on this box
                        # in between (e.g. the nightly cron trigger firing
                        # during that gap) and never got to its own post{}
                        # cleanup because IT also hit a controller restart.
                        # That's a plausible source for the stray
                        # "emulator-5556" seen alongside this build's own
                        # "emulator-5554" in that build's post-cleanup "adb
                        # reconnect offline" step — a second, unrelated local
                        # AVD instance nobody tore down. The pkill just below
                        # only matches THIS build's own AVD name
                        # ($ANDROID_AVD_NAME), so it can't catch an orphan
                        # under a different name/port. This blanket kill runs
                        # immediately before this stage launches its own
                        # emulator (not earlier, so it can't collide with a
                        # still-legitimate concurrent process elsewhere in the
                        # pipeline) and is safe here because "Acquire
                        # Shared-Box Lock" guarantees no other build is
                        # actively running on this box right now.
                        killall -9 qemu-system-x86_64 2>/dev/null || true
                        sleep 1

                        pkill -9 -f "emulator.*-avd $ANDROID_AVD_NAME" 2>/dev/null || true
                        pkill -9 -f "appium" 2>/dev/null || true
                        # NOTE: this used to also `adb kill-server` here as a
                        # "belt-and-braces" measure. ROOT CAUSE (2026-08-07
                        # build): that's a race, not a fix. adb kill-server
                        # asks the currently-running server to exit but does
                        # NOT wait for the port to be released; the very next
                        # line below launches the emulator, which immediately
                        # tries to register its transport with adb — and
                        # "adb wait-for-device" a few lines down then forces a
                        # brand-new adb server to spawn ("daemon not running;
                        # starting now at tcp:5037", visible in that build's
                        # own log) at almost the same instant. Whichever adb
                        # server the emulator's transport registered with can
                        # end up being the one that's already exiting, leaving
                        # the device stuck "offline" for the rest of the
                        # build (the emulator's own emulator.log shows this:
                        # "Boot completed in 18501 ms" internally, yet every
                        # `adb shell` call against it fails with "device
                        # offline" from that point on) — a known class of adb
                        # bug when the server restarts while a transport is
                        # attaching. Just starting (not killing+restarting)
                        # the server here is enough to clear genuinely stale
                        # state left by a previous crashed run without
                        # racing this run's own emulator: adb start-server
                        # is a no-op if a server is already up, and if there
                        # isn't one, this establishes it well before the
                        # emulator launches instead of concurrently with it.
                        "$ANDROID_SDK_ROOT/platform-tools/adb" start-server 2>/dev/null || true

                        # -no-snapshot-load: cleanWs() in post{} wipes the
                        # entire workspace (including .android-sdk/avd/) after
                        # every single build, so a "default_boot" snapshot
                        # from a prior run can never exist here — without this
                        # flag the emulator always wastes time attempting (and
                        # failing) to load a snapshot that is guaranteed not
                        # to be there, e.g.:
                        #   WARNING  Device 'cache' does not have the requested snapshot 'default_boot'
                        #   WARNING  Failed to load snapshot 'default_boot'
                        # before falling back to the cold boot it was always
                        # going to need. Skipping straight to cold boot removes
                        # that dead-end detour from the critical boot path.
                        #
                        # -port 5554 pins this emulator's adb serial to the
                        # fixed "emulator-5554" (ANDROID_SERIAL above) instead
                        # of letting adb auto-assign whichever even-numbered
                        # port is free — auto-assignment is exactly how the
                        # Genymotion device and this AVD could otherwise end
                        # up ambiguous to a bare `adb` call.
                        "$ANDROID_SDK_ROOT/emulator/emulator" -avd "$ANDROID_AVD_NAME" -port 5554 -accel "$ACCEL_MODE" -no-window -no-audio -no-boot-anim -no-snapshot-load -gpu swiftshader_indirect -camera-back none > emulator.log 2>&1 &

                        echo "Waiting for emulator to boot..."
                        # ANDROID_SERIAL=emulator-5554 (exported above) scopes
                        # every adb call below to just this stage's own AVD,
                        # even though Amit's persistent Genymotion device
                        # (127.0.0.1:6562) is also connected to this same adb
                        # server — without it, adb refuses any command with
                        # "error: more than one device/emulator" the moment
                        # both devices are registered.
                        #
                        # 300s (not 120s): this agent's own emulator.log shows
                        # KVM running degraded here — "host doesn't support
                        # requested feature: CPUID.01H:ECX.aes" plus "Setting
                        # AVD to run with 1 vCPU core only" — a signature of
                        # nested virtualization without full passthrough. That
                        # slows the guest boot and the adb transport handshake
                        # well past 120s even on a run that ultimately
                        # succeeds (a real run recorded "Boot completed in
                        # 44062 ms" internally, yet every adb call was still
                        # "device offline" long after that point). The 120s
                        # gate was also inconsistent with the *next* loop
                        # below, which already budgets 60x5s=300s of patience
                        # for the same "device isn't ready yet" condition —
                        # this shorter, earlier gate was aborting the stage
                        # before that more patient loop ever got a chance to
                        # run. Matching both to 300s removes that inconsistency.
                        #
                        # WAIT_SECS scales with ACCEL_MODE: forcing "-accel
                        # off" above (see the capability check near the top of
                        # this script) trades speed for reliability — a cold
                        # software-emulated boot genuinely takes longer than a
                        # working hardware-accelerated one, so a fixed 300s
                        # budget that was already tight under degraded/
                        # half-broken "auto" acceleration would be even more
                        # likely to time out here. Both the wait-for-device
                        # gate and the boot_completed poll loop below use this
                        # same budget so neither cuts the other off early.
                        WAIT_SECS=300
                        POLL_ITERS=60
                        if [ "$ACCEL_MODE" = "off" ]; then
                            WAIT_SECS=600
                            POLL_ITERS=120
                        fi
                        if ! timeout $WAIT_SECS "$ANDROID_SDK_ROOT/platform-tools/adb" wait-for-device; then
                            echo "ERROR: emulator did not come up within ${WAIT_SECS}s — it may have crashed. Check emulator.log below."
                            cat emulator.log 2>/dev/null || true
                            exit 1
                        fi
                        BOOTED=0
                        for i in $(seq 1 $POLL_ITERS); do
                            boot_completed=$("$ANDROID_SDK_ROOT/platform-tools/adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\\r')
                            if [ "$boot_completed" = "1" ]; then
                                echo "Emulator booted"
                                BOOTED=1
                                break
                            fi
                            # Mid-loop nudge for the adb-server-race case above:
                            # if the device is still registered as "offline"
                            # (as opposed to genuinely still booting), a plain
                            # `adb reconnect` re-probes the existing transport
                            # without restarting the server or the emulator —
                            # cheap, best-effort, and exactly what the post{}
                            # block's own "ADB memory-leak cleanup" step already
                            # relies on to clear this same state after the fact.
                            # Only tried every ~25s (not every 5s) so it doesn't
                            # spam a device that's simply still mid-boot.
                            if [ $((i % 5)) -eq 0 ]; then
                                if "$ANDROID_SDK_ROOT/platform-tools/adb" devices | grep -q "^${ANDROID_SERIAL}[[:space:]]*offline"; then
                                    echo "Device registered but offline at attempt $i — trying adb reconnect..."
                                    "$ANDROID_SDK_ROOT/platform-tools/adb" reconnect 2>/dev/null || true
                                fi
                            fi
                            sleep 5
                        done
                        # adb wait-for-device above only requires the device to be
                        # REGISTERED (it can still be "offline", exactly what this
                        # box has shown under degraded/no-KVM boots) — it does not
                        # guarantee sys.boot_completed ever reaches "1". Without
                        # this check, a device that stays offline for the full
                        # 300s here fell through silently into starting Appium and
                        # running mvn test against a half-booted device, producing
                        # confusing Appium/test failures instead of the same clear
                        # infra-timeout diagnosis the wait-for-device gate above
                        # already gives.
                        if [ "$BOOTED" != "1" ]; then
                            echo "ERROR: emulator registered but never reached sys.boot_completed=1 within ${WAIT_SECS}s — likely still 'offline' (see adb devices below). Check emulator.log."
                            "$ANDROID_SDK_ROOT/platform-tools/adb" devices || true
                            cat emulator.log 2>/dev/null || true
                            exit 1
                        fi

                        appium --log-timestamp --log-no-colors > appium.log 2>&1 &
                        for i in $(seq 1 30); do
                            if curl -s http://127.0.0.1:4723/status > /dev/null; then
                                echo "Appium is up"
                                break
                            fi
                            echo "Waiting for Appium... ($i)"
                            sleep 2
                        done

                        # mobile.properties is deliberately gitignored (real local
                        # device values, not something to commit) — cleanWs() in
                        # post{} wipes the whole workspace every build, so it never
                        # exists here either way. SiteRegistry.validate("mobile")
                        # requires it to exist before any mobile test starts, even
                        # though every value that matters is overridden via -D flags
                        # below. Materialize it from the tracked .example template,
                        # same as the GitHub Actions / GitLab CI mobile jobs.
                        cp src/test/resources/config/mobile.properties.example \\
                           src/test/resources/config/mobile.properties
                    '''

                        sh 'mkdir -p target/jacoco-artifacts'
                        def suiteFile = "testng-suites/mobile-${params.SUITE_TYPE}.xml"
                        int exitCode = sh(
                                script: """
                           mvn -B -ntp test \\
                              -Dsite=mobile \\
                              -DsuiteXmlFile=${suiteFile} \\
                              -Dmobile.app.package=com.android.settings \\
                              -Dmobile.app.activity=.Settings \\
                              -Dmobile.device.name=\$ANDROID_SERIAL \\
                              -Dretry.count=${params.RETRY_COUNT} \\
                              -Dallure.results.directory=target/allure-results/mobile \\
                              -Djacoco.destFile=target/jacoco-artifacts/mobile.exec \\
                              -Dsurefire.reportsDirectory=target/surefire-reports/mobile \\
                              -Dmaven.test.failure.ignore=true
                        """,
                                returnStatus: true
                        )

                        sh '''
                        pkill -f "emulator.*-avd $ANDROID_AVD_NAME" 2>/dev/null || true
                        pkill -f "appium" 2>/dev/null || true
                    '''

                        if (exitCode != 0) {
                            currentBuild.result = 'UNSTABLE'
                            echo "Mobile suite had failures."
                        }
                    }
                } // catchError
            }
        }

        stage('Coverage Gate') {
            // Runs after Run Tests Per Site + Mobile Test so every branch's
            // target/jacoco-artifacts/<key>.exec (see -Djacoco.destFile
            // above) is already on disk. Each individual branch only
            // exercises the slice of core/ its own suite touches, so
            // checking any single one against the 50% core/ threshold in
            // pom.xml would fail unfairly — merge them into one
            // target/jacoco.exec first, then gate on the union. Marked
            // UNSTABLE (not a hard failure) on a threshold breach, same
            // pattern as every other quality signal in this pipeline, so a
            // coverage dip is visible without blocking the whole build.
            steps {
                script {
                    def hasExecFiles = sh(
                            script: 'ls target/jacoco-artifacts/*.exec 2>/dev/null | head -1',
                            returnStdout: true
                    ).trim()

                    if (!hasExecFiles) {
                        echo 'No jacoco-artifacts/*.exec files found — skipping coverage gate for this run.'
                        return
                    }

                    sh '''
                        mkdir -p target/jacoco-raw
                        cp target/jacoco-artifacts/*.exec target/jacoco-raw/
                        mvn -B -ntp jacoco:merge@jacoco-merge
                        mvn -B -ntp jacoco:report@jacoco-report
                    '''

                    int exitCode = sh(
                            script: 'mvn -B -ntp jacoco:check@jacoco-check',
                            returnStatus: true
                    )
                    if (exitCode != 0) {
                        currentBuild.result = 'UNSTABLE'
                        echo 'Coverage gate failed — com.automation.core.* line coverage is under the 50% threshold. See target/site/jacoco/index.html.'
                    }
                }
            }
        }

        stage('Nightly Extra Coverage') {
            // Only demoqa has these two suite files, and they're
            // intentionally excluded from the regular SUITE_TYPE choice
            // (['regression', 'smoke']) above — this stage is the only
            // place they run, and only on the nightly cron trigger, not on
            // every manually-triggered or commit-triggered build.
            when {
                expression {
                    currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause').size() > 0
                }
            }
            steps {
                script {
                    // ExtentManager names every report "<site>-index.html" with
                    // no suite-type distinction. The mv at the end of each loop
                    // iteration below only guards against accessibility/visual
                    // overwriting EACH OTHER — it runs too late to save the
                    // regular regression report: on a cron-triggered build
                    // (default params = SITE=ALL), the earlier "Run Tests Per
                    // Site" stage already wrote a real demoqa-index.html, and
                    // this stage's very first mvn test call (accessibility)
                    // overwrites it with fresh accessibility-only content
                    // BEFORE any mv runs — silently destroying that regression
                    // report on every nightly build. Snapshot it out of the way
                    // first so it survives under its own name.
                    sh "mv target/extent-reports/demoqa-index.html target/extent-reports/demoqa-regression-index.html 2>/dev/null || true"

                    def extraSuites = ['accessibility', 'visual']
                    def extraResultDirs = []
                    def extraFailures = []

                    extraSuites.each { suite ->
                        def suiteFile = "testng-suites/demoqa-${suite}.xml"
                        if (fileExists(suiteFile)) {
                            echo "==== Running nightly demoqa ${suite} suite ===="
                            def resultDir = "demoqa-${suite}"
                            int exitCode = sh(
                                    script: """
                                   mvn -B -ntp test \\
                                      -Dsite=demoqa \\
                                      -DsuiteXmlFile=${suiteFile} \\
                                      -Dbrowser=${params.BROWSER} \\
                                      -Dheadless=${params.HEADLESS} \\
                                      -Dhuman.pause.enabled=false \\
                                      -Dallure.results.directory=target/allure-results/${resultDir} \\
                                      -Dsurefire.reportsDirectory=target/surefire-reports/${resultDir} \\
                                      -Dmaven.test.failure.ignore=true
                                """,
                                    returnStatus: true
                            )
                            extraResultDirs.add(resultDir)
                            if (exitCode != 0) {
                                extraFailures.add(suite)
                            }
                            // ExtentManager names the report "<site>-index.html"
                            // with no suite-type distinction — since this stage
                            // runs -Dsite=demoqa for both accessibility and
                            // visual, and the regular per-site stage above may
                            // have just run -Dsite=demoqa too (on a
                            // cron-triggered build with the default SITE=ALL),
                            // every one of these runs would otherwise overwrite
                            // the exact same file. Rename it out of the way
                            // immediately so each suite's report survives.
                            sh "mv target/extent-reports/demoqa-index.html target/extent-reports/demoqa-${suite}-index.html 2>/dev/null || true"
                        } else {
                            echo "Skipping ${suite}: ${suiteFile} not found"
                        }
                    }

                    env.NIGHTLY_RESULT_DIRS = extraResultDirs.join(',')
                    if (extraFailures) {
                        currentBuild.result = 'UNSTABLE'
                        echo "Nightly suites with failures: ${extraFailures.join(', ')}"
                    }
                }
            }
        }

        stage('Performance Smoke (Nightly)') {
            // Same cron-only gating as "Nightly Extra Coverage" above —
            // perf/basic-smoke.jmx is a lightweight response-time smoke
            // check (see the .jmx file's own TestPlan.comments), not a
            // load/capacity test, so it runs alongside the other nightly
            // extras rather than on every build. Uses the pom.xml `perf`
            // Maven profile — jmeter-maven-plugin resolves JMeter itself
            // via Maven into .m2/repository, so there's no separate
            // JMeter download/cache to manage here.
            when {
                expression {
                    currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause').size() > 0
                }
            }
            steps {
                script {
                    int exitCode = sh(
                            script: 'mvn -B -ntp verify -Pperf',
                            returnStatus: true
                    )
                    if (exitCode != 0) {
                        // Same reasoning as the .gitlab-ci.yml equivalent:
                        // a transient slow response from a site this repo
                        // doesn't control shouldn't fail the whole nightly
                        // build the way a real functional regression
                        // should — mark UNSTABLE, don't throw.
                        currentBuild.result = 'UNSTABLE'
                        echo "Performance smoke check reported issues (exit ${exitCode}) — see target/jmeter/reports"
                    }
                }
            }
        }
        stage('Security Scan (Nightly)') {
            // Same cron-only gating as "Nightly Extra Coverage" and
            // "Performance Smoke" above — OWASP Dependency-Check's first
            // run downloads/builds the NVD CVE database locally, which is
            // slow (several minutes), so this runs nightly rather than on
            // every build. SECURITY_FAIL_CVSS defaults to 11 (never
            // fails) — report-only until the team deliberately opts into
            // gating builds on it via that build parameter, same
            // reasoning as the "security" profile's own comment in
            // pom.xml.
            when {
                expression {
                    currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause').size() > 0
                }
            }
            steps {
                script {
                    int exitCode = sh(
                            script: "mvn -B -ntp verify -Psecurity -DfailBuildOnCVSS=${params.SECURITY_FAIL_CVSS}",
                            returnStatus: true
                    )
                    if (exitCode != 0) {
                        currentBuild.result = 'UNSTABLE'
                        echo "OWASP Dependency-Check reported issues (exit ${exitCode}) — see target/dependency-check-report.html"
                    }
                    archiveArtifacts artifacts: 'target/dependency-check-report.*', allowEmptyArchive: true
                }
            }
        }
    }

    post {
        always {
            // Cleanup/reporting runs directly in the current post{} context
            // by default (see runCleanup() below), with a node('') fallback
            // only for the rare case where that context is actually gone —
            // e.g. an early "Declarative: Checkout SCM" failure, or an agent
            // crash mid-build — which surfaces as:
            //   org.jenkinsci.plugins.workflow.steps.MissingContextVariableException:
            //   Required context class hudson.FilePath is missing
            // Without that fallback, a build that dies hard enough to lose
            // its node context would never release /tmp/selenium-framework-
            // pipeline.lockdir (a plain `mkdir`-based mutex with no other
            // release path), hanging every subsequent build at "Acquire
            // Shared-Box Lock" forever.
            script {
                // ROOT CAUSE (2026-08-07 build, diagnosed from console log):
                // this block used to unconditionally wrap everything below in
                // node('') { ws(originalWorkspace) { ... } } "just in case"
                // the original node/workspace context had been torn down
                // before post{} ran. That defensive wrap was the bug: the
                // outer `agent any` node is still alive and still HOLDS THE
                // LOCK on this build's real workspace for the entire
                // pipeline, including post{} — so node('') grabbing a brand
                // new executor and then ws(originalWorkspace) trying to lock
                // that SAME path a second time can never get it; Jenkins
                // hands back a disambiguated, empty directory instead
                // (".../Selenium-Automation-Framework-Pipeline@2", then a
                // further "@3" for the nested ws()). Every step below then
                // silently ran against that empty directory: junit reported
                // "No test report files were found", allure reported
                // "allure-results does not exist", archiveArtifacts reported
                // "doesn't match anything" for target/extent-reports,
                // target/screenshots, target/jacoco-artifacts, etc, and the
                // self-healing locator-repository cache-write step no-op'd
                // too — even though every one of those files existed and was
                // correct in the real workspace the whole time.
                //
                // Fix: run the cleanup directly in the current (still valid,
                // still correctly-pathed) context first — this is what
                // succeeds on the overwhelming majority of builds, including
                // this one. Only fall back to reclaiming a fresh node/
                // workspace if that direct attempt proves the original
                // context is actually gone (MissingContextVariableException,
                // the exact failure this fallback exists for), in which case
                // there is no "real" workspace left to pin back to anyway —
                // the cleanup runs best-effort in whatever new workspace
                // node('') hands out.
                def runCleanup = {
                    // ── Release shared-box lock ─────────────────────
                    // First thing here, unconditionally and best-effort,
                    // so the next queued build (or a stuck one from a
                    // hard-killed agent) is never left waiting on this
                    // build's own reporting/archiving steps below. See
                    // "Acquire Shared-Box Lock" stage.
                    sh '''
                        rm -rf /tmp/selenium-framework-pipeline.lockdir 2>/dev/null || true
                    '''

                    // ── JUnit results ───────────────────────────────
                    // Must be `**/*.xml`, not a flat `*.xml`: the "Run Tests
                    // Per Site"/"Mobile Test"/"Nightly Extra Coverage" stages
                    // all pass -Dsurefire.reportsDirectory=target/surefire-
                    // reports/<key> (see the surefire.reportsDirectory
                    // property in pom.xml) so concurrent `mvn test` branches
                    // don't clobber one shared TEST-TestSuite.xml. A flat
                    // glob only matches files directly under
                    // target/surefire-reports/ and silently misses every one
                    // of those nested per-site/per-mobile/per-nightly-suite
                    // result files, which is indistinguishable from "no
                    // tests ran" in the junit step's own output.
                    junit allowEmptyResults: true,
                            testResults: 'target/surefire-reports/**/*.xml'

                    // ── Allure Report ───────────────────────────────
                    // One results dir per site (see "Run Tests Per Site"
                    // stage), plus the nightly accessibility/visual dirs
                    // when the "Nightly Extra Coverage" stage ran — pass
                    // all of them so the single generated report covers
                    // every suite that ran, not just whichever happened
                    // to write last.
                    def resultDirs = env.SITES_TO_RUN?.trim()
                            ? env.SITES_TO_RUN.split(',').collect { [path: "target/allure-results/${it}"] }
                            : []
                    if (env.RUN_MOBILE == 'true') {
                        resultDirs += [[path: 'target/allure-results/mobile']]
                    }
                    if (!resultDirs) {
                        resultDirs = [[path: 'target/allure-results']]
                    }
                    if (env.NIGHTLY_RESULT_DIRS) {
                        resultDirs += env.NIGHTLY_RESULT_DIRS.split(',').collect { [path: "target/allure-results/${it}"] }
                    }
                    allure([
                            includeProperties: false,
                            jdk: '',
                            results: resultDirs
                    ])

                    // ── Extent Report (HTML Publisher) ──────────────
                    if (fileExists('target/extent-reports')) {
                        publishHTML(target: [
                                allowMissing         : true,
                                alwaysLinkToLastBuild: true,
                                keepAll              : true,
                                reportDir            : 'target/extent-reports',
                                reportFiles          : '*.html',
                                reportName           : 'Extent Test Report'
                        ])
                    }

                    // ── Merged JaCoCo Coverage Report (HTML Publisher) ──
                    // Written by the Coverage Gate stage's
                    // jacoco:report@jacoco-report call, if that stage
                    // ran and found any *.exec artifacts to merge —
                    // reuses the same HTML Publisher plugin as the
                    // Extent report above rather than requiring the
                    // separate Jenkins JaCoCo plugin.
                    if (fileExists('target/site/jacoco/index.html')) {
                        publishHTML(target: [
                                allowMissing         : true,
                                alwaysLinkToLastBuild: true,
                                keepAll              : true,
                                reportDir            : 'target/site/jacoco',
                                reportFiles          : 'index.html',
                                reportName           : 'JaCoCo Coverage Report (merged)'
                        ])
                    }

                    // ── Archive raw artifacts ───────────────────────
                    archiveArtifacts allowEmptyArchive: true,
                            artifacts: 'target/extent-reports/**, target/screenshots/**, target/allure-results/**, target/jmeter/results/**, target/jmeter/reports/**, target/site/jacoco/**, target/jacoco-artifacts/**',
                            fingerprint: true

                    // ── ADB memory-leak cleanup ─────────────────────
                    // "adb reconnect offline" forces adb to drop and
                    // re-probe any device/emulator connection it still
                    // thinks is live, which is the fix for the specific
                    // case that bit us on this box: a build that ran the
                    // Mobile Test stage leaves adb's own server process
                    // running (by design — it's meant to be reused by
                    // the next build to skip a slow cold restart), but
                    // each new emulator boot across many local runs was
                    // gradually growing that server's memory footprint.
                    // Only runs when the mobile stage actually executed
                    // and left an adb binary behind — a browser-only
                    // build (RUN_MOBILE == 'false') has neither, so this
                    // step is a deliberate no-op there rather than an
                    // error.
                    if (env.RUN_MOBILE == 'true') {
                        sh '''
                                ADB_BIN="${ANDROID_SDK_ROOT:-${WORKSPACE}/.android-sdk}/platform-tools/adb"
                                if [ -x "$ADB_BIN" ]; then
                                    # This post block runs outside the Mobile
                                    # Test stage's own `environment {}`, so
                                    # ANDROID_SERIAL isn't set here
                                    # automatically — export it so this
                                    # reconnect targets only the CI emulator
                                    # and doesn't touch (or error out
                                    # against) the separately-connected
                                    # Genymotion device.
                                    ANDROID_SERIAL=emulator-5554 "$ADB_BIN" reconnect offline 2>/dev/null || true
                                fi
                            '''
                    }

                    // ── Cache Self-Healing Repository ───────────────
                    // See "Restore Self-Healing Cache" stage above for
                    // the full reasoning — this is the write half of
                    // that same round trip. Must run before cleanWs()
                    // below, which deletes the workspace (including
                    // self-healing-data/) unconditionally.
                    sh '''
                            CACHE_DIR="${JENKINS_HOME}/selfhealing-cache/${JOB_NAME}"
                            if [ -f "self-healing-data/locator-repository.json" ]; then
                                mkdir -p "$CACHE_DIR"
                                cp self-healing-data/locator-repository.json "$CACHE_DIR/locator-repository.json"
                            fi
                        '''

                    // ── Workspace cleanup ───────────────────────────
                    // Runs last, after artifacts are already archived
                    // above, so nothing needed by this build's own
                    // report/archive steps is lost — this only clears
                    // disk for the NEXT build (and the NVMe SSD's free
                    // space generally, given how much target/ and
                    // .android-sdk/ accumulate per run on this box).
                    cleanWs(
                            deleteDirs: true,
                            notFailBuild: true
                    )
                }

                try {
                    // Common case: the outer `agent any` context is still
                    // live (true for every normal build, UNSTABLE or not —
                    // this is what actually ran in the 2026-08-07 log this
                    // fix was diagnosed from). Runs directly, no re-wrap.
                    runCleanup()
                } catch (org.jenkinsci.plugins.workflow.steps.MissingContextVariableException mcve) {
                    // Rare case: the original node/workspace context really
                    // is gone (e.g. an early "Declarative: Checkout SCM"
                    // failure, or an agent crash mid-build). There is no
                    // real workspace left to pin back to, so this just
                    // reclaims whatever fresh executor is available and
                    // does its best from an empty directory — good enough
                    // to at least release the shared-box lock.
                    echo "Original workspace context is gone (${mcve.message}) — reclaiming a fresh executor for best-effort cleanup."
                    try {
                        node('') {
                            runCleanup()
                        }
                    } catch (Throwable t) {
                        echo "post{always{}} cleanup could not run (no executor available to reclaim: ${t.message}) — the build's real failure is above this message, not this one."
                    }
                }
            }
        }

        unstable {
            echo 'UNSTABLE: one or more sites had test failures. Check Allure and Extent reports.'
        }
        failure {
            echo 'FAILED: check Build or Discover stage logs.'
        }
    }
}
