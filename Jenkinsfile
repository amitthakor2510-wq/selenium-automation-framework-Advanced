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
        buildDiscarder(logRotator(numToKeepStr: '20'))
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

                    env.SITES_TO_RUN = (params.SITE == 'ALL')
                            ? allSites.join(',')
                            : params.SITE

                    echo "Sites discovered: ${allSites.join(', ')}"
                    echo "Sites this run: ${env.SITES_TO_RUN}"
                }
            }
        }

        stage('Run Tests Per Site') {
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
                def resultDirs = env.SITES_TO_RUN
                        ? env.SITES_TO_RUN.split(',').collect { [path: "target/allure-results/${it}"] }
                        : [[path: 'target/allure-results']]
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
        }

        unstable {
            echo 'UNSTABLE: one or more sites had test failures. Check Allure and Extent reports.'
        }
        failure {
            echo 'FAILED: check Build or Discover stage logs.'
        }
    }
}
