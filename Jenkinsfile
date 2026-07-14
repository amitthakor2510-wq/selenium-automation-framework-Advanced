// =============================================================
// Jenkins CI/CD pipeline - Selenium + Java + TestNG + Maven
// Requires:
//   - JDK 17            (Tools > JDK, name: "JDK17")
//   - Maven             (Tools > Maven, name: "Maven installations")
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

                    sites.each { site ->
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
                                  -Dmaven.test.failure.ignore=true
                            """,
                                returnStatus: true
                        )
                        testResults[site] = exitCode
                    }

                    def failedSites = testResults.findAll { k, v -> v != 0 }.keySet()
                    if (failedSites) {
                        currentBuild.result = 'UNSTABLE'
                        echo "Sites with failures: ${failedSites.join(', ')}"
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
            allure([
                    includeProperties: false,
                    jdk: '',
                    results: [[path: 'target/allure-results']]
            ])

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
