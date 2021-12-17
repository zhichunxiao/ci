properties([
    parameters([
        string(
            defaultValue: 'debug-pipeline2',
            name: 'TRIGGER_JOB_NAME',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'GHPRB_TARGET_BRANCH',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'GHPRB_ACTUAL_COMMIT',
            trim: true,
        ),
        string(
            defaultValue: 'debug-pipeline2',
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

def run_with_pod(Closure body) {
    def label = "atom-jenkins-it-trigger-${BUILD_NUMBER}"
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.16'
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: true,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}


@NonCPS // has to be NonCPS or the build breaks on the call to .each
def parseBuildResult(list) {
    def total_test = 0
    def failed_test = 0
    def success_test = 0

    list.each { item ->
        echo "${item}"
        if (item.status == "success") {
            success_test += 1
        } else {
            failed_test += 1
        }
    }
    total_test = success_test + failed_test

    return [
        "total_test": total_test, 
        "success_test": success_test, 
        "failed_test": failed_test
    ]
        
}

run_with_pod {
    container("golang") {
        try {
            def ws = pwd()
            def summary = [:]
            def resultStrValid = false

            stage("Debug INFO") {
                println "${TASK_NAME}"
                println "trigger job name: ${TRIGGER_JOB_NAME}"
            }

            stage("Trigger job") {
                default_params = [
                        string(name: 'triggered_by_upstream_ci', value: "TRIGGER_EVENT"),
                        booleanParam(name: 'release_test', value: true),
                        string(name: 'release_test__release_branch', value: GHPRB_TARGET_BRANCH),
                        string(name: 'release_test__tidb_commit', value: GHPRB_ACTUAL_COMMIT),
                ]
                dir("${ws}/${TRIGGER_JOB_NAME}") {
                       result = build(job: "${TRIGGER_JOB_NAME}", parameters: default_params, wait: true)
                       buildResultInStr = result.getDescription()
                       if (result.getResult() != "SUCCESS") {
                           currentBuild.result = "FAILURE"
                       }
                       if (result.getDescription() != null && result.getDescription() != "") {
                            resultStrValid = true
                            println result.getDescription()
                            def jsonObj = readJSON text: result.getDescription()
                            sh """
                            echo ${jsonObj}[0].status
                            """
                            summary = parseBuildResult(jsonObj)
                       }
                }
            }

            stage("Summary") {
                if ( resultStrValid ) {
                    println "total_test: ${summary["total_test"]}"
                    println "success_test: ${summary["success_test"]}"
                    println "failed_test: ${summary["failed_test"]}"
                    currentBuild.description = "total_test: ${summary.total_test}, success_test: ${summary.success_test}, failed_test: ${summary.failed_test}"
                } else {
                    println "No result string"
                }
            }
        }  
        catch (err) {
            throw err
        } finally {
            sh """
            """
        }
    }
}
