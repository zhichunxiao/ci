
/*
* input
* @REPO(string: github repo name, Required)
* @COMMIT_ID(string: commit id request id, Required)
* @CACHE_CODE_FILESERVER_URL(string: cached code on fileserver, Required)
* @LINT_CMD(txt: cmd to run lint check, Required)
*
*
* doc
*/



properties([
        parameters([
                string(
                        defaultValue: 'tiem',
                        name: 'REPO',
                        trim: true
                ),
                string(
                        defaultValue: '36c442509f86d58a69004059ba0ac3b74e4e4051',
                        name: 'COMMIT_ID',
                        trim: true,
                ),
                string(
                        defaultValue: 'http://fileserver.pingcap.net/download/builds/pingcap/devops/cachecode/tidb/086a63f4713416ae5976eecc5a4d7c40f6121079/tidb.tar.gz',
                        name: 'CACHE_CODE_FILESERVER_URL',
                        trim: true
                ),
                text(
                        defaultValue: 'golangci-lint run  --out-format=junit-xml  --timeout=10m  --config ./.golangci.yml  -v ./... > golangci-lint-report.xml',
                        name: 'LINT_CMD',
                        trim: true
                ),
        ])
])


LINT_CONFIG_URL = "https://raw.githubusercontent.com/purelind/devops-config/purelind/tiem-init-golangci-lint/tiem/golangci.yaml"


def run_with_pod(Closure body) {
    def label = "cache-code-atom-job"
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def pod_go_docker_image = "hub-new.pingcap.net/jenkins/centos7_golang-1.16"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '8000m', resourceRequestMemory: '8Gi',
                            resourceLimitCpu: '20000m', resourceLimitMemory: "20Gi",
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOMODCACHE', value: '/nfs/cache/mod'),
                                      containerEnvVar(key: 'GOPATH', value: '/go')],
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
            volumes: [
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
                    nfsVolume(mountPath: '/nfs/cache', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs', readOnly: false),
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

try {
    run_with_pod {
        container("golang") {
            try {
                def ws = pwd()

                dir("${REPO}") {
                    stage("Download code from fileserver") {
                        sh """
                    curl ${CACHE_CODE_FILESERVER_URL} | tar xz --strip-components=1
                    """
                    }

                    stage("Download lint conf") {
                        sh """
                    curl -L -o .golangci.yml ${LINT_CONFIG_URL}
                    """
                    }

                    stage("Lint check") {
                        sh """
                        curl -sfL https://raw.githubusercontent.com/golangci/golangci-lint/master/install.sh| sh -s -- -b ${ws}/bin v1.42.0
                        """
                        sh """
                        export PATH=${ws}/bin:\$PATH
                        golangci-lint version
                        
                        ${LINT_CMD}
                        """
                    }
                }
                currentBuild.result = "SUCCESS"

            } catch (err) {
                throw err
            } finally {
                junit(
                    allowEmptyResults: true,
                    testResults: "${REPO}/golangci-lint-report.xml"
                )
            }
        }
    }
}

catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
} catch (hudson.AbortException e) {
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
    } else {
        currentBuild.result = "FAILURE"
    }
} catch (InterruptedException e) {
    println e
    currentBuild.result = "ABORTED"
}
catch (Exception e) {
        currentBuild.result = "FAILURE"
        echo "${e}"
}


