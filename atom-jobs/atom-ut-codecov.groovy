properties([
    parameters([
        string(
            defaultValue: 'tiem',
            name: 'REPO',
            trim: true
        ),
        string(
            defaultValue: 'http://fileserver.pingcap.net/download/builds/pingcap/devops/cachecode/tiem/36c442509f86d58a69004059ba0ac3b74e4e4051/tiem.tar.gz',
            name: 'CACHE_CODE_FILESERVER_URL',
            trim: true
        ),
        string(
            defaultValue: '36c442509f86d58a69004059ba0ac3b74e4e4051',
            name: 'COMMIT_ID',
            trim: true,
        ),
        text(
            defaultValue: """
go test -v ./... -coverprofile=cover.out | go-junit-report > test.xml
            """,
            name: 'TEST_CMD',
            trim: true
        ),
        string(
            defaultValue: 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest',
            name: 'TEST_ENV',
            trim: true,
        ),
        string(
            defaultValue: 'test_report',
            name: 'UT_REPORT_DIR',
            trim: true,
        ),
        string(
            defaultValue: '50',
            name: 'COVERAGE_RATE',
            trim: true,
        ),
        string(
            defaultValue: '',
            name: 'TRIGGER_EVENT',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'BRANCH',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'PULL_REQUEST_ID',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'TASK_NAME',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'SECRET_VARS',
            trim: true
        ),
    ])
])

def run_with_pod(Closure body) {
    def label = "atom-ut-codecov-${BUILD_NUMBER}"
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest'
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: true,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '2Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars:[containerEnvVar(key: 'GOPATH', value: '/go')],
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
            volumes: [
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}


run_with_pod {
    container("golang") {
        try {
            def ws = pwd()

            stage("${TASK_NAME}") {
                println "${TASK_NAME}"
            }

            stage("Download binary from fileserver") {
                sh '''
                    wget -q -c ${CACHE_CODE_FILESERVER_URL} -O - | tar -xz
                '''
            }

            credentialList =[]
            varStrings = SECRET_VARS.split(",")
            for (varString in varStrings) {
                keyAndValue = varString.split(":")
                if (keyAndValue.length == 2) {
                    credentialList.push(string(credentialsId: keyAndValue[0], variable: keyAndValue[1]))
                }
            }

            stage("Test") {
                dir("${ws}/${REPO}") {
                    sh '''
                        go get github.com/jstemmer/go-junit-report
                        go get github.com/axw/gocov/gocov
                        go get github.com/AlekSi/gocov-xml
                    '''
                    withCredentials(credentialList) {
                        sh TEST_CMD
                    }
                }
            }

            stage("Codecov") {
                // upload coverage report to codecov
                // read coverage rate from codecov api
                dir("${ws}/${REPO}") {
                    // coverage file: test_report/*coverage.out
                    withCredentials(credentialList) {
                        timeout(5) {
                            if (PULL_REQUEST_ID != null && PULL_REQUEST_ID != "") {
                                sh """
                                curl -LO ${FILE_SERVER_URL}/download/cicd/ci-tools/codecov
                                chmod +x codecov
                                ./codecov -f   -t ${CODECOV_TOKEN} -C ${ghprbActualCommit} -P ${ghprbPullId} -b ${BUILD_NUMBER}
                                """
                            } else {
                                sh """
                                curl -LO ${FILE_SERVER_URL}/download/cicd/ci-tools/codecov
                                chmod +x codecov
                                ./codecov -f  -t ${CODECOV_TOKEN} -C ${ghprbActualCommit} -b ${BUILD_NUMBER} -B ${ghprbTargetBranch}
                                """
                            }
                        }
                    }
                }
            }

        }  
        catch (err) {
            throw err
        } finally {
            sh """
                wget ${FILE_SERVER_URL}/download/rd-atom-agent/atom-ut/agent-ut.py
                python3 agent-ut.py ${REPO}/${UT_REPORT_DIR} ${REPO}/${COV_REPORT_DIR} ${COVERAGE_RATE}
                
                wget ${FILE_SERVER_URL}/download/rd-index-agent/repo_ut/tiinsight-agent-ut.py
                python3 tiinsight-agent-ut.py ${REPO} ${BRANCH} ${COMMIT_ID} ${TASK_NAME} ${REPO}/${COV_REPORT_DIR} ${REPO}/${UT_REPORT_DIR}
            """
            ENV_TEST_SUMMARY = sh(script: "cat test_summary.info", returnStdout: true).trim()
            println ENV_TEST_SUMMARY
            currentBuild.description = "${ENV_TEST_SUMMARY}"

            junit testResults: "${REPO}/${UT_REPORT_DIR}"
            if (currentBuild.result == 'UNSTABLE') {
                currentBuild.result = 'FAILURE'
            }
            echo currentBuild.result
            cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: "${REPO}/${COV_REPORT_DIR}", lineCoverageTargets: "${COVERAGE_RATE}, ${COVERAGE_RATE}, ${COVERAGE_RATE}", maxNumberOfBuilds: 10, onlyStable: false, sourceEncoding: 'ASCII', zoomCoverageChart: false
            echo currentBuild.result
        }
    }
}
