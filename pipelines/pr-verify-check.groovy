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
        for (task in configs.tasks) {
            taskType = task.taskType.toString()
            taskName =task.name.toString()
            switch(taskType) {
                case "build":
                    def buildConfig = common.parseBuildConfig(task)
                    jobs[taskName] = {
                        common.buildBinary(buildConfig,repo,ghprbActualCommit,ghprbTargetBranch,taskName,"pull_request")
                    }
                    break
                case "unit-test":
                    def unitTestConfig = common.parseUnitTestConfig(task)
                    jobs[taskName] = {
                        common.unitTest(unitTestConfig,repo,ghprbActualCommit,ghprbTargetBranch,taskName,"pull_request")
                    }
                    break
                case "lint":
                    def lintConfig = common.parseLintConfig(task)
                    jobs[taskName] = {
                        common.codeLint(lintConfig,repo,ghprbActualCommit,ghprbTargetBranch,taskName,"pull_request")
                    }
                    break
                case "cyclo": 
                    def cycloConfig = common.parseCycloConfig(task)
                    jobs[taskName] = {
                        common.codeCyclo(cycloConfig,repo,ghprbActualCommit,ghprbTargetBranch,taskName,"pull_request")
                    }
                    break
                case "gosec":
                    def gosecConfig = common.parseGosecConfig(task)
                    jobs[taskName] = {
                        common.codeGosec(gosecConfig,repo,ghprbActualCommit,ghprbTargetBranch,taskName,"pull_request")
                    }
                    break
                case "common":
                    def commonConfig = common.parseCommonConfig(task)
                    jobs[taskName] = {
                        common.codeCommon(commonConfig,repo,ghprbActualCommit,ghprbTargetBranch,taskName,"pull_request")
                    }
                    break
            }
        }
        stage("verify") {
            parallel jobs
        }
    }
}
