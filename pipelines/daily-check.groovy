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

// >> TODO remote debug code here
REPO = 'pingcap/tidb'
repo = "tidb"
org = "pingcap"
branch = "master"
// << TODO remote debug code here


def get_sha(branch) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${branch} -s=${FILE_SERVER_URL}").trim()
}

def configfile = "https://raw.githubusercontent.com/PingCAP-QE/devops-config/master/${repo}/daily.yaml"

// >> TODO remote debug code here
// configfile = "https://raw.githubusercontent.com/purelind/test-ci/main/dailyci.yaml"
configfile = "https://raw.githubusercontent.com/purelind/test-ci/main/dailyci-deubug.yaml"


def runtasks(branch,repo,commitID,tasks,common) {
    jobs = [:]
    def task_result_array = []
    for (task in tasks) {
        def taskType = task.taskType.toString()
        def taskName =task.name.toString()
        switch(taskType) {
            case "build":
                def buildConfig = common.parseBuildConfig(task)
                jobs[taskName] = {
                    def result_map = common.buildBinary(buildConfig,repo,commitID,branch,taskName,"daily")
                    task_result_array << result_map
                    // if (result_map.taskResult != "SUCCESS") {
                    //     throw new Exception("task failed")
                    // }
                }
                break
            case "unit-test":
                def unitTestConfig = common.parseUnitTestConfig(task)
                jobs[taskName] = {
                    common.unitTest(unitTestConfig,repo,commitID,branch,taskName,"daily")
                }
                break
            case "lint":
                def lintConfig = common.parseLintConfig(task)
                jobs[taskName] = {
                    common.codeLint(lintConfig,repo,commitID,branch,taskName,"daily")
                }
                break
            case "cyclo": 
                def cycloConfig = common.parseCycloConfig(task)
                jobs[taskName] = {
                    def result_map = common.codeCyclo(cycloConfig,repo,commitID,branch,taskName,"daily")
                    task_result_array << result_map
                    // if (result_map.taskResult != "SUCCESS") {
                    //     throw new Exception("task failed")
                    // }
                }
                break
            case "gosec":
                def gosecConfig = common.parseGosecConfig(task)
                jobs[taskName] = {
                    common.codeGosec(gosecConfig,repo,commitID,branch,taskName,"daily")
                }
                break
            case "common":
                def commonConfig = common.parseCommonConfig(task)
                jobs[taskName] = {
                    common.codeCommon(commonConfig,repo,commitID,branch,taskName,"daily")
                }
                break
        }
    }
    
    try {
        parallel jobs
    } catch (e) {
        throw new Exception("task failed")
    } finally {
        stage("summary") {
            // println task_result_array
            for (result_map in task_result_array) {
                if (result_map.taskResult != "SUCCESS") {
                    println "${result_map.name} task failed"
                }
                if (result_map.taskSummary != null && result_map.taskSummary != "") {
                    println("${result_map.name} ${result_map.taskResult}: ${result_map.taskSummary}")
                    println("${result_map.name} #${result_map.buildNumber}: ${result_map.url}")
                }
            }
        }

        return task_result_array
    }
}

node("${GO_BUILD_SLAVE}") {
    container("golang") {
        // checkout scm
        // def common = load "pipelines/common.groovy"
        // >> TODO remote debug code here
        sh "wget https://raw.githubusercontent.com/purelind/jenkins-templates/purelind/task-summary/pipelines/common.groovy"
        def common = load "common.groovy"
        // << TODO remote debug code here

        configs = common.getConfig(configfile)
        refs  = configs.defaultRefs
        taskFailed = false
        for (ref in refs) {
            def commitID = get_sha(ref)
            try {
                stage("verify: " + ref) {
                    common.cacheCode(REPO,commitID,ref,"")
                    def task_result_array = runtasks(ref,repo,commitID,configs.tasks,common) 
                    for (result_map in task_result_array) {
                        println result_map.name
                    }
                }     
            } catch (Exception e) {
                taskFailed = true
            }           
        }
        if (taskFailed) {
            throw new RuntimeException("task failed")
        }
    }
}
