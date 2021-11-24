repo = "" // chanage to origin repo
repoInfo = ghprbGhRepository.split("/")
if (repoInfo.length == 2) {
    repo = repoInfo[1]
}

def configfile = "https://raw.githubusercontent.com/PingCAP-QE/devops-config/main/${repo}/verify.yaml"


node("${GO_BUILD_SLAVE}") {
    container("golang") {
        checkout scm
        def common = load "pipelines/common.groovy"
        configs = common.getConfig(configfile)
        stage("get code") {
            common.cacheCode(ghprbGhRepository,ghprbActualCommit,"",ghprbPullId)
        }
        jobs = [:]
        def task_result_array = []
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
    }
}
