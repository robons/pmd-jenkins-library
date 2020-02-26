def call(body) {
    def pipelineParams = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def FAILED_STAGE

    pipeline {
        agent {
            label 'master'
        }
        environment {
            DATASET_DIR = "datasets/${JOB_BASE_NAME}"
        }
        stages {
            stage('Clean') {
                steps {
                    script {
                        FAILED_STAGE = env.STAGE_NAME
                        sh "rm -rf ${DATASET_DIR}/out"
                    }
                }
            }
            stage('Transform') {
                agent {
                    docker {
                        image 'gsscogs/databaker'
                        reuseNode true
                        alwaysPull true
                    }
                }
                steps {
                    script {
                        FAILED_STAGE = env.STAGE_NAME
                        ansiColor('xterm') {
                            if (fileExists("${DATASET_DIR}/main.py")) {
                                sh "jupytext --to notebook ${DATASET_DIR}/*.py"
                            }
                            sh "jupyter-nbconvert --output-dir=${DATASET_DIR}/out --ExecutePreprocessor.timeout=None --execute '${DATASET_DIR}/main.ipynb'"
                        }
                    }
                }
            }
            stage('Validate CSV') {
                agent {
                    docker {
                        image 'gsscogs/csvlint'
                        reuseNode true
                        alwaysPull true
                    }
                }
                steps {
                    script {
                        FAILED_STAGE = env.STAGE_NAME
                        ansiColor('xterm') {
                            if (fileExists("${DATASET_DIR}/schema.json")) {
                                sh "csvlint --no-verbose -s ${DATASET_DIR}/schema.json"
                            } else {
                                def schemas = []
                                for (def schema : findFiles(glob: "${DATASET_DIR}/out/*-schema.json")) {
                                    schemas.add("${DATASET_DIR}/out/${schema.name}")
                                }
                                for (String schema : schemas) {
                                    sh "csvlint --no-verbose -s '${schema}'"
                                }
                            }
                        }
                    }
                }
            }
            stage('Upload Tidy Data') {
                steps {
                    script {
                        FAILED_STAGE = env.STAGE_NAME
                        jobDraft.replace()
                        def datasets = []
                        String dspath = util.slugise(env.JOB_NAME)
                        def outputFiles = findFiles(glob: "${DATASET_DIR}/out/*.csv")
                        if (outputFiles.length == 0) {
                            error(message: "No output CSV files found")
                        } else {
                            for (def observations : outputFiles) {
                                String thisPath = (outputFiles.length == 1) ? dspath : "${dspath}/${observations.name.take(observations.name.lastIndexOf('.'))}"
                                def dataset = [
                                        "csv"     : "${DATASET_DIR}/out/${observations.name}",
                                        "metadata": "${DATASET_DIR}/out/${observations.name}-metadata.trig",
                                        "path"    : thisPath
                                ]
                                datasets.add(dataset)
                            }
                        }
                        for (def dataset : datasets) {
                            uploadTidy([dataset.csv],
                                    "reference/columns.csv",
                                    dataset.path,
                                    dataset.metadata)
                        }
                    }
                }
            }
            stage('Test draft dataset') {
                agent {
                    docker {
                        image 'gsscogs/gdp-sparql-tests'
                        reuseNode true
                        alwaysPull true
                    }
                }
                steps {
                    script {
                        FAILED_STAGE = env.STAGE_NAME
                        pmd = pmdConfig("pmd")
                        String draftId = pmd.drafter.findDraftset(env.JOB_NAME).id
                        String endpoint = pmd.drafter.getDraftsetEndpoint(draftId)
                        String dspath = util.slugise(env.JOB_NAME)
                        def dsgraphs = []
                        if (fileExists("${DATASET_DIR}/out/observations.csv")) {
                            dsgraphs.add("${pmd.config.base_uri}/graph/${dspath}")
                        } else {
                            for (def observations : findFiles(glob: "${DATASET_DIR}/out/*.csv")) {
                                String basename = observations.name.take(observations.name.lastIndexOf('.'))
                                dsgraphs.add("${pmd.config.base_uri}/graph/${dspath}/${basename}")
                            }
                        }
                        withCredentials([usernamePassword(credentialsId: pmd.config.credentials, usernameVariable: 'USER', passwordVariable: 'PASS')]) {
                            for (String dsgraph : dsgraphs) {
                                sh "sparql-test-runner -t /usr/local/tests -s ${endpoint}?union-with-live=true -a '${USER}:${PASS}' -p \"dsgraph=<${dsgraph}>\" -r 'reports/TESTS-${dsgraph.substring(dsgraph.lastIndexOf('/') + 1)}.xml'"
                            }
                        }
                    }
                }
            }
            stage('Submit for review') {
                steps {
                    script {
                        FAILED_STAGE = env.STAGE_NAME
                        pmd = pmdConfig("pmd")
                        String draftId = pmd.drafter.findDraftset(env.JOB_NAME).id
                        pmd.drafter.submitDraftsetTo(draftId, uk.org.floop.jenkins_pmd.Drafter.Role.EDITOR, null)
                    }
                }
            }
        }
        post {
            always {
                script {
                    String main_issue
                    if (fileExists("${DATASET_DIR}/info.json")) {
                        def info = readJSON(text: readFile(file: "${DATASET_DIR}/info.json"))
                        if (info.containsKey('transform') && info['transform'].containsKey('main_issue')) {
                            main_issue = info['transform']['main_issue']
                        }
                    }
                    String issueBody = """Jenkins job result: ${currentBuild.result}
Stage: ${FAILED_STAGE}
[View full output]($BUILD_URL)
"""
                    if (main_issue) {
                        issueBody = issueBody + """
Blocks #${main_issue}
"""
                    }
                    step([$class     : 'GitHubIssueNotifier',
                          issueAppend: true,
                          issueLabel : 'Jenkins',
                          issueTitle : '$JOB_BASE_NAME failed',
                          issueBody  : issueBody])
                    archiveArtifacts artifacts: "${DATASET_DIR}/out/*", excludes: "${DATASET_DIR}/out/*.html"
                    junit allowEmptyResults: true, testResults: 'reports/**/*.xml'
                    publishHTML([
                            allowMissing: true, alwaysLinkToLastBuild: true, keepAll: true,
                            reportDir   : "${DATASET_DIR}/out", reportFiles: 'main.html',
                            reportName  : 'Transform'])
                }
            }
        }
    }
}