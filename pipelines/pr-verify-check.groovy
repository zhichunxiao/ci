repo = "" // chanage to origin repo
org = ""
repoInfo = ghprbGhRepository.split("/")
if (repoInfo.length == 2) {
    repo = repoInfo[1]
    org = repoInfo[0]
}

def taskStartTimeInMillis = System.currentTimeMillis()
def configfile = "https://raw.githubusercontent.com/PingCAP-QE/devops-config/main/${repo}/verify.yaml"


node("${GO1160_BUILD_SLAVE}") {
    container("golang") {
        checkout scm
        def common = load "pipelines/common.groovy"
        configs = common.getConfig(configfile)
        stage("get code") {
            common.cacheCode(ghprbGhRepository,ghprbActualCommit,"",ghprbPullId)
        }
        jobs = [:]
        def all_results = []
        def task_result_array = []
        try {
            
            for (task in configs.tasks) {
                def taskType = task.taskType.toString()
                def taskName =task.name.toString()
                switch(taskType) {
                    case "build":
                        def buildConfig = common.parseBuildConfig(task)
                        jobs[taskName] = {
                            def result = common.buildBinary(buildConfig,repo,ghprbActualCommit,ghprbTargetBranch,taskName,"pull_request")
                            task_result_array << ["name": taskName, "type": taskType, "result": result]
                            if (result.getResult() != "SUCCESS") {
                                throw new Exception("${taskName} failed")
                            }
                        }
                        break
                    case "unit-test":
                        def unitTestConfig = common.parseUnitTestConfig(task)
                        jobs[taskName] = {
                            def result = common.unitTest(unitTestConfig,repo,ghprbActualCommit,ghprbTargetBranch,taskName,"pull_request")
                            task_result_array << ["name": taskName, "type": taskType, "result": result]
                            if (result.getResult() != "SUCCESS") {
                                throw new Exception("${taskName} failed")
                            }
                        }
                        break
                    case "lint":
                        def lintConfig = common.parseLintConfig(task)
                        jobs[taskName] = {
                            def result = common.codeLint(lintConfig,repo,ghprbActualCommit,ghprbTargetBranch,taskName,"pull_request")
                            task_result_array << ["name": taskName, "type": taskType, "result": result]
                            if (result.getResult() != "SUCCESS") {
                                throw new Exception("${taskName} failed")
                            }
                        }
                        break
                    case "cyclo": 
                        def cycloConfig = common.parseCycloConfig(task)
                        jobs[taskName] = {
                            def result = common.codeCyclo(cycloConfig,repo,ghprbActualCommit,ghprbTargetBranch,taskName,"pull_request")
                            task_result_array << ["name": taskName, "type": taskType, "result": result]
                            if (result.getResult() != "SUCCESS") {
                                throw new Exception("${taskName} failed")
                            }
                        }
                        break
                    case "gosec":
                        def gosecConfig = common.parseGosecConfig(task)
                        jobs[taskName] = {
                            def result = common.codeGosec(gosecConfig,repo,ghprbActualCommit,ghprbTargetBranch,taskName,"pull_request")
                            task_result_array << ["name": taskName, "type": taskType, "result": result]
                            if (result.getResult() != "SUCCESS") {
                                throw new Exception("${taskName} failed")
                            }
                        }
                        break
                    case "common":
                        def commonConfig = common.parseCommonConfig(task)
                        jobs[taskName] = {
                            def result = common.codeCommon(commonConfig,repo,ghprbActualCommit,ghprbTargetBranch,taskName,"pull_request")
                            task_result_array << ["name": taskName, "type": taskType, "result": result]
                            if (result.getResult() != "SUCCESS") {
                                throw new Exception("${taskName} failed")
                            }
                        }
                        break
                }
            }
            stage("verify") {
                parallel jobs
            }
        } catch (e) {
            currentBuild.result = "FAILURE"
        } finally {
            stage("summary") {
                for (result_map in task_result_array) {
                    all_results << [name: result_map.name, 
                        type: result_map.type,
                        result: result_map.result.getResult(), 
                        fullDisplayName: result_map.result.getFullDisplayName(), 
                        buildNumber: result_map.result.getNumber().toString(),
                        summary: result_map.result.getDescription(),
                        durationStr: result_map.result.getDurationString(),
                        duration: result_map.result.getDuration(),
                        startTime: result_map.result.getStartTimeInMillis(),
                        url: "${CI_JENKINS_BASE_URL}/blue/organizations/jenkins/${result_map.result.getFullProjectName()}/detail/${result_map.result.getFullProjectName()}/${result_map.result.getNumber().toString()}/pipeline"
                        ]
                    if (result_map.result.getDescription() != null && result_map.result.getDescription() != "") {
                        println "${result_map.name} ${result_map.result.getResult()}: ${result_map.result.getDescription()}"       
                    } else {
                        println "${result_map.name} ${result_map.result.getResult()}"       
                    }
                }

                def trigger = ghprbPullAuthorLogin
                if ( ghprbTriggerAuthorLogin != "" ) {
                    trigger = ghprbTriggerAuthorLogin
                }
                all_results << [name: JOB_NAME,
                    result: currentBuild.result,
                    buildNumber: BUILD_NUMBER,
                    type: "verifyci-pipeline",
                    commitID: ghprbActualCommit, 
                    prID: ghprbPullId,
                    branch: ghprbTargetBranch,
                    repo: repo,
                    org: org,
                    url: "${env.RUN_DISPLAY_URL}", 
                    startTime: taskStartTimeInMillis, 
                    duration: System.currentTimeMillis() - taskStartTimeInMillis,
                    trigger: trigger            
                    ]
                def lark_notify_github_id = []
                lark_notify_github_id << ghprbPullAuthorLogin
                if ( ghprbTriggerAuthorLogin != "" && ghprbTriggerAuthorLogin != ghprbPullAuthorLogin) {
                    lark_notify_github_id << ghprbTriggerAuthorLogin
                }
                all_results << [name: "ci-notify",
                    type: "ci-notify",
                    lark: lark_notify_github_id]
                def json = groovy.json.JsonOutput.toJson(all_results)
                println "all_results: ${json}"
                writeJSON file: 'ciResult.json', json: json, pretty: 4
                sh 'cat ciResult.json'
                archiveArtifacts artifacts: 'ciResult.json', fingerprint: true

                if (config.get("notifyPolicy") != null && config.get("notifyPolicy") == "IfPipelineFail") {
                    if (currentBuild.result == "FAILURE") {
                        sh """
                            wget ${FILE_SERVER_URL}/download/rd-atom-agent/agent-verifyci.py
                            python3 agent-verifyci.py ciResult.json
                        """  
                    }
                }
            } 
        }
    }
}
