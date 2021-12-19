properties([
    parameters([
        string(
            defaultValue: 'tidb',
            name: 'REPO',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'TASK_NAME',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'TRIGGER_EVENT',
            trim: true
        ),
    ])
])

utfHost = "http://172.16.4.180:31080/"

triggerCI = httpRequest url: utfHost + "dailyci/trigger", httpMode: 'POST'
ciResp = readJSON text: triggerCI.content
id = ciResp["id"].toString()
ciFinished = false
ciDuration = 0
while(!ciFinished) {
    sleep(300)
    ciDuration = ciDuration +300
    // ci breaks when timeout(23 hours)
    if (ciDuration > 82800) {
        echo "daily ci timeout reached, stop waiting task complete and collect result now."
        break
    }
    statusCI = httpRequest utfHost + "dailyci/trigger/" + id
    statusResp = readJSON text: statusCI.content
    ciFinished = statusResp["finished"].toBoolean()
}


def run_with_pod(Closure body) {
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def label = "atom-utf-${BUILD_NUMBER}"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest'
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [ontainerEnvVar(key: 'GOPATH', value: '/go')],
                            
                    )
            ],
            volumes: [
                            emptyDirVolume(mountPath: '/tmp', memory: false),
                            emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

run_with_pod {
    container("golang"){
        stage("utf daily ci result") {
            sh """
            wget -O ./utf.xml ${utfHost}dailyci?id=${id}
            wget ${FILE_SERVER_URL}/download/rd-index-agent/repo_utf/tiinsight-agent-utf.py
            python3 tiinsight-agent-utf.py "${REPO}" ${branch} "empty" utf.xml

            wget ${FILE_SERVER_URL}/download/rd-atom-agent/atom-utf/agent-utf.py
            python3 agent-utf.py ${branch} utf.xml
            """
            ENV_UTF_SUMMARY = sh(script: "cat utf_summary.info", returnStdout: true).trim()
            println ENV_UTF_SUMMARY
            currentBuild.description = "${ENV_UTF_SUMMARY}"


            junit testResults: "**/utf.xml"
        }
    }
}
