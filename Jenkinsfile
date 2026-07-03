// =============================================================
// Jenkins CI/CD pipeline for the Selenium + Java + TestNG +
// Maven automation framework.
//
// SCALABILITY:
// Site projects are auto-discovered from testng-suites/*-<TYPE>.xml
// (e.g. demoqa-regression.xml -> site "demoqa"). Adding a new site
// project (new package under src/test/java/com/automation/sites/<name>,
// a config/<name>.properties file, and a testng-suites/<name>-regression.xml
// suite) is picked up automatically on the next run - no Jenkinsfile
// edits required.
//
// Requires on the Jenkins controller/agent:
//   - JDK 17            (Manage Jenkins > Tools > JDK installations, name: "JDK17")
//   - Maven 3.9+         (Manage Jenkins > Tools > Maven installations, name: "Maven3")
//   - HTML Publisher plugin (for the Extent report)
//   - Chrome / Firefox / Edge installed on the agent if not running headless
// =============================================================

pipeline {

    agent any

    tools {
        jdk 'JDK17'
        maven 'Maven3'
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
            description: 'Site to test, e.g. "demoqa". Use "ALL" to run every site project found under testng-suites/'
        )
        choice(
            name: 'BROWSER',
            choices: ['chrome', 'firefox', 'edge'],
            description: 'Browser to run against'
        )
        booleanParam(
            name: 'HEADLESS',
            defaultValue: true,
            description: 'Run browser headless (recommended for CI agents)'
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
                        error "No testng-suites/*-${params.SUITE_TYPE}.xml files found. Nothing to run."
                    }

                    def allSites = suiteFiles.split('\n').collect { path ->
                        def fileName = path.tokenize('/').last()
                        fileName.replace("-${params.SUITE_TYPE}.xml", '')
                    }

                    env.SITES_TO_RUN = (params.SITE == 'ALL')
                        ? allSites.join(',')
                        : params.SITE

                    echo "Sites discovered: ${allSites.join(', ')}"
                    echo "Sites this run will execute: ${env.SITES_TO_RUN}"
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

                        echo "==== Running ${params.SUITE_TYPE} suite for site: ${site} ===="

                        int exitCode = sh(
                            script: """
                                mvn -B -ntp test \
                                    -Dsite=${site} \
                                    -DsuiteXmlFile=${suiteFile} \
                                    -Dbrowser=${params.BROWSER} \
                                    -Dheadless=${params.HEADLESS}
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
            junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'

            archiveArtifacts allowEmptyArchive: true,
                artifacts: 'target/extent-reports/**, target/screenshots/**',
                fingerprint: true

            script {
                if (fileExists('target/extent-reports')) {
                    publishHTML(target: [
                        allowMissing: true,
                        alwaysLinkToLastBuild: true,
                        keepAll: true,
                        reportDir: 'target/extent-reports',
                        reportFiles: '*.html',
                        reportName: 'Extent Test Report'
                    ])
                }
            }
        }
        unstable {
            echo 'Build finished UNSTABLE: one or more sites had test failures. See Extent report(s) and JUnit results for details.'
        }
        failure {
            echo 'Build FAILED: check the "Build" or "Discover Site Projects" stage logs.'
        }
    }
}
