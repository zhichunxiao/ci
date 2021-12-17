properties([
        parameters([
                string(
                        defaultValue: 'pingcap/tidb',
                        name: 'REPO',
                        trim: true
                )
        ]),
        pipelineTriggers([cron('H H * * *')])
])

repo = "" // chanage to origin repo
org = ""
repoInfo = REPO.split("/")
if (repoInfo.length == 2) {
    org = repoInfo[0]
    repo = repoInfo[1]
}

def taskStartTimeInMillis = System.currentTimeMillis()

def get_sha(branch) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${branch} -s=${FILE_SERVER_URL}").trim()
}

def configfile = "https://raw.githubusercontent.com/PingCAP-QE/devops-config/master/${repo}/daily.yaml"


def runtasks(branch,repo,commitID,tasks,common,task_result_array) {
    jobs = [:]
    for (task in tasks) {
        def taskType = task.taskType.toString()
        def taskName =task.name.toString()
        switch(taskType) {
            case "build":
                def buildConfig = common.parseBuildConfig(task)
                jobs[taskName] = {
                    def result = common.buildBinary(buildConfig,repo,commitID,branch,taskName,"daily")
                    task_result_array << ["name": taskName, "type": taskType, "result": result]
                    if (result.getResult() != "SUCCESS") {
                        throw new Exception("${taskName} failed")
                    }
                }
                break
            case "unit-test":
                def unitTestConfig = common.parseUnitTestConfig(task)
                jobs[taskName] = {
                    def result = common.unitTest(unitTestConfig,repo,commitID,branch,taskName,"daily")
                    task_result_array << ["name": taskName, "type": taskType, "result": result]
                    if (result.getResult() != "SUCCESS") {
                        throw new Exception("${taskName} failed")
                    }
                }
                break
            case "lint":
                def lintConfig = common.parseLintConfig(task)
                jobs[taskName] = {
                    def result = common.codeLint(lintConfig,repo,commitID,branch,taskName,"daily")
                    task_result_array << ["name": taskName, "type": taskType, "result": result]
                    if (result.getResult() != "SUCCESS") {
                        throw new Exception("${taskName} failed")
                    }
                }
                break
            case "cyclo": 
                def cycloConfig = common.parseCycloConfig(task)
                jobs[taskName] = {
                    def result = common.codeCyclo(cycloConfig,repo,commitID,branch,taskName,"daily")
                    task_result_array << ["name": taskName, "type": taskType, "result": result]
                    if (result.getResult() != "SUCCESS") {
                        throw new Exception("${taskName} failed")
                    }
                }
                break
            case "gosec":
                def gosecConfig = common.parseGosecConfig(task)
                jobs[taskName] = {
                    def result = common.codeGosec(gosecConfig,repo,commitID,branch,taskName,"daily")
                    task_result_array << ["name": taskName, "type": taskType, "result": result]
                    if (result.getResult() != "SUCCESS") {
                        throw new Exception("${taskName} failed")
                    }
                }
                break
            case "common":
                def commonConfig = common.parseCommonConfig(task)
                jobs[taskName] = {
                    def result = common.codeCommon(commonConfig,repo,commitID,branch,taskName,"daily")
                    task_result_array << ["name": taskName, "type": taskType, "result": result]
                    if (result.getResult() != "SUCCESS") {
                        throw new Exception("${taskName} failed")
                    }
                }
                break
            case "jenkins-it-trigger":
                def jenkinsITConfig = common.parseJenkinsITConfig(task)
                // DEBUG
                println jenkinsITConfig
                println taskName
                println taskType
                jobs[taskName] = {
                    def result = common.jenkinsItTrigger(jenkinsITConfig,ghprbTargetBranch,ghprbActualCommit,taskName,"daily")
                    println "result: ${result.getResult()}"
                    task_result_array << ["name": taskName, "type": taskType, "result": result]
                    if (result.getResult() != "SUCCESS") {
                        throw new Exception("${taskName} failed")
                    }
                }
                break
        }
    }

    parallel jobs
}

node("${GO1160_BUILD_SLAVE}") {
    container("golang") {
        checkout scm
        def common = load "pipelines/common.groovy"

        configs = common.getConfig(configfile)
        ref  = configs.defaultRef
        taskFailed = false
        def all_results = []
        def commitID = get_sha(ref)
        def task_result_array = []
        try {
            stage(ref) {
                common.cacheCode(REPO,commitID,ref,"")
                runtasks(ref,repo,commitID,configs.tasks,common,task_result_array)       
            }     
        } catch (Exception e) {
            taskFailed = true
            currentBuild.result = "FAILURE"
        } finally {
            stage("daily summary") {
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
            } 
        }           
        
        all_results << [name: JOB_NAME,
            result: currentBuild.result,
            buildNumber: BUILD_NUMBER,
            type: "dailyci-pipeline",
            commitID: commitID, 
            branch: "master",
            repo: repo,
            org: org,
            url: "${env.RUN_DISPLAY_URL}", 
            startTime: taskStartTimeInMillis, 
            duration: System.currentTimeMillis() - taskStartTimeInMillis,
            trigger: "daily"            
            ]
        all_results << [name: "ci-notify",
            type: "ci-notify",
            lark: configs.notify.lark,
            email: configs.notify.email]
        def json = groovy.json.JsonOutput.toJson(all_results)
        println "all_results: ${json}"
        writeJSON file: 'dailyciResult.json', json: json, pretty: 4
        sh 'cat dailyciResult.json'
        archiveArtifacts artifacts: 'dailyciResult.json', fingerprint: true

        if (currentBuild.result == "FAILURE") {
            sh """
                wget ${FILE_SERVER_URL}/download/rd-atom-agent/agent-dailyci.py
                python3 agent-dailyci.py dailyciResult.json
            """  
        }
    }
}
