properties([
    parameters([
        string(
            defaultValue: 'tiem',
            name: 'REPO',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'CACHE_CODE_FILESERVER_URL',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'COMMIT_ID',
            trim: true,
        ),
        text(
            defaultValue: """
            """,
            name: 'TEST_CMD',
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
            defaultValue: 'coverage_report',
            name: 'COVERAGE_REPORT_DIR',
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


lines_coverage_rate = ""

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
                    sh TEST_CMD
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
                                ./codecov -f 'coverage_report/*.coverage'  -t ${CODECOV_TOKEN} -C ${COMMIT_ID} -P ${PULL_REQUEST_ID} -b ${BUILD_NUMBER}
                                """
                            } else {
                                sh """
                                curl -LO ${FILE_SERVER_URL}/download/cicd/ci-tools/codecov
                                chmod +x codecov
                                ./codecov -f 'coverage_report/*.coverage' -t ${CODECOV_TOKEN} -C ${COMMIT_ID} -b ${BUILD_NUMBER} -B ${BRANCH}
                                """
                            }
                        }
                        sleep(time:120,unit:"SECONDS")
                        def response = httpRequest Authorization: CODECOV_API_TOKEN, url: "https://codecov.io/api/gh/pingcap/tidb/commit/${COMMIT_ID}"
                        println('Status: '+response.status)
                        def obj = readJSON text:response.content
                        if (response.status == 200) {
                            println(obj.commit.totals)
                            lines_coverage_rate = "Lines coverage ${obj.commit.totals.c.toFloat().round(2)}%."
                            println('Coverage: '+obj.commit.totals.c)
                            println("Files count: "+ obj.commit.totals.f)
                            println("Lines count: "+obj.commit.totals.n)
                            println("Hits count: "+obj.commit.totals.h)
                            println("Misses count: "+obj.commit.totals.m)
                            println("Paritials count: "+obj.commit.totals.p)

                            println('Coverage: '+obj.commit.totals.diff[5])
                            println("Files count: "+ obj.commit.totals.diff[0])
                            println("Lines count: "+obj.commit.totals.diff[1])
                            println("Hits count: "+obj.commit.totals.diff[2])
                            println("Misses count: "+obj.commit.totals.diff[3])
                            println("Paritials count: "+obj.commit.totals.diff[4])
                        } else {
                            println('Error: '+response.content)
                            println('Status not 200: '+response.status)
                        }
                    }
                }
            }

        }  
        catch (err) {
            println "error found======"
            echo "Caught: ${err}"
            throw err
        } finally {
            sh """
                wget ${FILE_SERVER_URL}/download/rd-atom-agent/atom-ut/agent-ut-codecov.py
                python3 agent-ut-codecov.py ${REPO}/${UT_REPORT_DIR} || true
                
            """
            ENV_TEST_SUMMARY = sh(script: "cat test_summary.info", returnStdout: true).trim()
            println ENV_TEST_SUMMARY
            currentBuild.description = "${ENV_TEST_SUMMARY}"
            if (lines_coverage_rate != "") {
                currentBuild.description = currentBuild.description + lines_coverage_rate
            }
            println currentBuild.description

            junit testResults: "${REPO}/${UT_REPORT_DIR}"
            if (currentBuild.result == 'UNSTABLE') {
                currentBuild.result = 'FAILURE'
            }
        }
    }
}
