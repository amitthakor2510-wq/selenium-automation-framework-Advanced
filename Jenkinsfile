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
                description: 'Browser to run against'
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

        stage('Cleanup (Stale Processes)') {
            // Runs FIRST, before checkout, so a crashed previous build never
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
                    echo "Killing any orphaned node/adb/qemu processes from a previous crashed run..."
                    killall -9 node 2>/dev/null || true
                    killall -9 adb 2>/dev/null || true
                    killall -9 qemu-system-x86_64 2>/dev/null || true

                    echo "Removing stale AVD/adb lock files..."
                    find "${HOME}/.android" -name "*.lock" -delete 2>/dev/null || true
                    find /tmp -maxdepth 1 -name "*.lock" -delete 2>/dev/null || true

                    echo "Cleanup complete."
                '''
            }
        }

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh 'mvn -B -ntp clean compile test-compile'
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
                    def testResults = [:]

                    // Each branch below runs `mvn test` as its own separate
                    // JVM, so a shared/global Maven local repo is safe to
                    // read from concurrently. The only genuinely shared
                    // OUTPUT that two sites running at once would otherwise
                    // collide on is target/allure-results/environment.properties
                    // (AllureEnvironmentWriter writes it once per JVM) — each
                    // branch is pointed at its own target/allure-results/<site>
                    // subdirectory to avoid that race. Extent's report file
                    // is already named "<site>-index.html" (ExtentManager),
                    // so it never collided even in the old sequential loop.
                    def branches = [:]
                    sites.each { site ->
                        branches[site] = {
                            def suiteFile = "testng-suites/${site}-${params.SUITE_TYPE}.xml"
                            echo "==== Running ${params.SUITE_TYPE} for site: ${site} ===="

                            int exitCode = sh(
                                    script: """
                                   mvn -B -ntp test \\
                                      -Dsite=${site} \\
                                      -DsuiteXmlFile=${suiteFile} \\
                                      -Dbrowser=${params.BROWSER} \\
                                      -Dheadless=${params.HEADLESS} \\
                                      -Dhuman.pause.enabled=false \\
                                      -Dretry.count=${params.RETRY_COUNT} \\
                                      -Dallure.results.directory=target/allure-results/${site} \\
                                      -Dmaven.test.failure.ignore=true
                                """,
                                    returnStatus: true
                            )
                            testResults[site] = exitCode
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
                script {
                    // Same "install if missing" pattern as GitHub
                    // Actions/GitLab CI use for their mobile jobs, so a
                    // node that already has these cached only downloads
                    // on a cold cache. This stage assumes the Jenkins
                    // agent has KVM available (/dev/kvm) the same way the
                    // other two pipelines require it — if it doesn't,
                    // the emulator will still boot but very slowly.
                    sh '''
                        set -e
                        if [ ! -e /dev/kvm ] || [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
                            echo "WARNING: /dev/kvm not accessible to this agent — emulator will run in slow software mode."
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

                        pkill -f "emulator.*-avd $ANDROID_AVD_NAME" 2>/dev/null || true
                        pkill -f "appium" 2>/dev/null || true

                        "$ANDROID_SDK_ROOT/emulator/emulator" -avd "$ANDROID_AVD_NAME" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -camera-back none > emulator.log 2>&1 &

                        echo "Waiting for emulator to boot..."
                        if ! timeout 120 "$ANDROID_SDK_ROOT/platform-tools/adb" wait-for-device; then
                            echo "ERROR: emulator did not come up within 120s — it may have crashed. Check emulator.log below."
                            cat emulator.log 2>/dev/null || true
                            exit 1
                        fi
                        for i in $(seq 1 60); do
                            boot_completed=$("$ANDROID_SDK_ROOT/platform-tools/adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\\r')
                            if [ "$boot_completed" = "1" ]; then
                                echo "Emulator booted"
                                break
                            fi
                            sleep 5
                        done

                        appium --log-timestamp --log-no-colors > appium.log 2>&1 &
                        for i in $(seq 1 30); do
                            if curl -s http://127.0.0.1:4723/status > /dev/null; then
                                echo "Appium is up"
                                break
                            fi
                            echo "Waiting for Appium... ($i)"
                            sleep 2
                        done
                    '''

                    def suiteFile = "testng-suites/mobile-${params.SUITE_TYPE}.xml"
                    int exitCode = sh(
                            script: """
                           mvn -B -ntp test \\
                              -Dsite=mobile \\
                              -DsuiteXmlFile=${suiteFile} \\
                              -Dmobile.app.package=com.android.settings \\
                              -Dmobile.app.activity=.Settings \\
                              -Dmobile.device.name=\$ANDROID_AVD_NAME \\
                              -Dretry.count=${params.RETRY_COUNT} \\
                              -Dallure.results.directory=target/allure-results/mobile \\
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
    }

    post {
        always {
            // ── JUnit results ─────────────────────────────────────────
            junit allowEmptyResults: true,
                    testResults: 'target/surefire-reports/*.xml'

            // ── Allure Report ─────────────────────────────────────────
            // One results dir per site (see "Run Tests Per Site" stage),
            // plus the nightly accessibility/visual dirs when the
            // "Nightly Extra Coverage" stage ran — pass all of them so the
            // single generated report covers every suite that ran, not
            // just whichever happened to write last.
            script {
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
            }

            // ── Extent Report (HTML Publisher) ────────────────────────
            script {
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
            }

            // ── Archive raw artifacts ─────────────────────────────────
            archiveArtifacts allowEmptyArchive: true,
                    artifacts: 'target/extent-reports/**, target/screenshots/**, target/allure-results/**',
                    fingerprint: true

            // ── ADB memory-leak cleanup ────────────────────────────────
            // "adb reconnect offline" forces adb to drop and re-probe any
            // device/emulator connection it still thinks is live, which is
            // the fix for the specific case that bit us on this box: a
            // build that ran the Mobile Test stage leaves adb's own server
            // process running (by design — it's meant to be reused by the
            // next build to skip a slow cold restart), but each new
            // emulator boot across many local runs was gradually growing
            // that server's memory footprint. Only runs when the mobile
            // stage actually executed and left an adb binary behind — a
            // browser-only build (RUN_MOBILE == 'false') has neither, so
            // this step is a deliberate no-op there rather than an error.
            script {
                if (env.RUN_MOBILE == 'true') {
                    sh '''
                        ADB_BIN="${ANDROID_SDK_ROOT:-${WORKSPACE}/.android-sdk}/platform-tools/adb"
                        if [ -x "$ADB_BIN" ]; then
                            "$ADB_BIN" reconnect offline 2>/dev/null || true
                        fi
                    '''
                }
            }

            // ── Workspace cleanup ──────────────────────────────────────
            // Runs last, after artifacts are already archived above, so
            // nothing needed by this build's own report/archive steps is
            // lost — this only clears disk for the NEXT build (and the
            // NVMe SSD's free space generally, given how much target/
            // and .android-sdk/ accumulate per run on this box).
            cleanWs(
                    deleteDirs: true,
                    notFailBuild: true
            )
        }

        unstable {
            echo 'UNSTABLE: one or more sites had test failures. Check Allure and Extent reports.'
        }
        failure {
            echo 'FAILED: check Build or Discover stage logs.'
        }
    }
}
