repo = "" // chanage to origin repo
repoInfo = ghprbGhRepository.split("/")
if (repoInfo.length == 2) {
    repo = repoInfo[1]
}

def configfile = "https://raw.githubusercontent.com/PingCAP-QE/devops-config/main/${repo}/verify.yaml"

class Resource {
   String cpu;
   String memory;
}

class Env {
   String image;
   Resource limit;
   Resource request;
}

class BuildConfig {
   String shellScript;
   String outputDir;
   Env env;
}

class UnitTestConfig {
   String shellScript;
   String utReportDir;
   String covReportDir;
   String coverageRate;
   Env env;
}

class LintConfig {
   String shellScript;
   String reportDir;
}

class GosecConfig {
    String shellScript;
    String reportDir;
}

class CycloConfig {
    String shellScript;
}

class CommonConfig {
    String shellScript;
    Env env;
}



def getConfig(fileURL) {
    sh "wget -qnc ${fileURL}"
    configs = readYaml (file: "verify.yaml")
    return configs
}


def parseBuildConfig(config) {
    def buildConfig = new BuildConfig()
    buildConfig.outputDir = config.outputDir.toString()
    buildConfig.shellScript = config.shellScript.toString()
    return buildConfig
}

def parseUnitTestConfig(config) {
    def unitTestConfig = new UnitTestConfig()
    unitTestConfig.utReportDir = config.utReportDir.toString()
    unitTestConfig.covReportDir = config.covReportDir.toString()
    unitTestConfig.shellScript = config.shellScript.toString()
    unitTestConfig.coverageRate = config.coverageRate.toString()
    return unitTestConfig
}

def parseLintConfig(config) {
    def lintConfig = new LintConfig()
    lintConfig.reportDir = config.reportDir.toString()
    lintConfig.shellScript = config.shellScript.toString()
    return lintConfig
}

def parseGosecConfig(config) {
    def gosecConfig = new GosecConfig()
    gosecConfig.reportDir = config.reportDir.toString()
    gosecConfig.shellScript = config.shellScript.toString()
    return gosecConfig
}

def parseCycloConfig(config) {
    def cycloConfig = new CycloConfig()
    cycloConfig.shellScript = config.shellScript.toString()
    return cycloConfig
}

def parseCommonConfig(config) {
    def commonConfig = new CommonConfig()
    commonConfig.shellScript = config.shellScript.toString()
    commonConfig.env = new Env()
    commonConfig.env.image = config.buildEnv.image.toString()
    return commonConfig
}


def getBlueOceanLink(obj, jobName) {
    def buildNumber

    if(obj instanceof Exception) {
        obj.toString().split(" ").each {
        if(it.contains("#")) {
            buildNumber = it.substring(1)
        }
        }
    } else if(obj instanceof org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper) {
        buildNumber = obj.getNumber()
    } else {
        return "Can not determine Blue Ocean link!!!"
    }

    return "For detailed information see: https://ci.pingcap.net/blue/organizations/jenkins/${jobName}/detail/${jobName}/${buildNumber}/pipeline"
}

def linkBuild(job, parameters) {
    def buildInfo
    try {
        buildInfo = build(job: job, parameters: parameters, wait: true, propagate: true)
    } catch (Exception e) {
        println getBlueOceanLink(e, job)
        throw e
    }
    println getBlueOceanLink(buildInfo, job)
}

cacheCodeUrl = "${FILE_SERVER_URL}/download/builds/pingcap/devops/cachecode/${repo}/${ghprbActualCommit}/${repo}.tar.gz"

def cacheCode() {
    cacheCodeParams = [
        string(name: 'ORG_AND_REPO', value: ghprbGhRepository),
        string(name: 'COMMIT_ID', value: ghprbActualCommit),
        string(name: 'PULL_ID', value: ghprbPullId),
    ]
    linkBuild(job: "cache-code", parameters: cacheCodeParams)
}

def buildBinary(buildConfig) {
    buildParams = [
        string(name: 'REPO', value: repo),
        string(name: 'CACHE_CODE_FILESERVER_URL', value: cacheCodeUrl),
        string(name: 'COMMIT_ID', value: ghprbActualCommit),
        text(name: 'BUILD_CMD', value: buildConfig.shellScript),
        string(name: 'BUILD_ENV', value: "hub-new.pingcap.net/jenkins/centos7_golang-1.16"),
        string(name: 'OUTPUT_DIR', value: "bin"),
    ]
    linkBuild(job: "atom-build", parameters: buildParams)
}

def codeLint(lintConfig) {
    lintParams = [
        string(name: 'REPO', value: repo),
        string(name: 'COMMIT_ID', value: ghprbActualCommit),
        string(name: 'CACHE_CODE_FILESERVER_URL', value: cacheCodeUrl),
        text(name: 'LINT_CMD', value: lintConfig.shellScript),
        string(name: 'REPORT_DIR', value: lintConfig.reportDir),
    ]
    linkBuild(job: "atom-lint", parameters: lintParams)
}

def unitTest(unitTestConfig) {
    buildParams = [
        string(name: 'REPO', value: repo),
        string(name: 'COMMIT_ID', value: ghprbActualCommit),
        string(name: 'CACHE_CODE_FILESERVER_URL', value: cacheCodeUrl),
        text(name: 'TEST_CMD', value: unitTestConfig.shellScript),
        string(name: 'UT_REPORT_DIR', value: unitTestConfig.utReportDir),
        string(name: 'COV_REPORT_DIR', value: unitTestConfig.covReportDir),
        string(name: 'COVERAGE_RATE', value: unitTestConfig.coverageRate),
        string(name: 'TEST_ENV', value: "hub-new.pingcap.net/jenkins/centos7_golang-1.16"),
    ]
    linkBuild(job: "atom-ut", parameters: buildParams)
}

def codeGosec(gosecConfig) {
    gosecParams = [
            string(name: 'REPO', value: repo),
            string(name: 'COMMIT_ID', value: ghprbActualCommit),
            string(name: 'CACHE_CODE_FILESERVER_URL', value: cacheCodeUrl),
            text(name: 'CMD', value: gosecConfig.shellScript),
            string(name: 'REPORT_DIR', value: gosecConfig.reportDir),
    ]
    linkBuild(job: "atom-gosec", parameters: gosecParams)
}

def codeCyclo(CycloConfig cycloConfig) {
    cycloParams = [
            string(name: 'REPO', value: repo),
            string(name: 'COMMIT_ID', value: ghprbActualCommit),
            string(name: 'CACHE_CODE_FILESERVER_URL', value: cacheCodeUrl),
            text(name: 'CYCLO_CMD', value: cycloConfig.shellScript),
    ]
    linkBuild(job: "atom-cyclo", parameters: cycloParams)
}

def codeCommon(CommonConfig commonConfig) {
    def image = "hub-new.pingcap.net/jenkins/centos7_golang-1.16"
    if (commonConfig.env.image != null && commonConfig.env.image != "") {
        image = commonConfig.env.image
    }
    commonParams = [
            string(name: 'REPO', value: repo),
            string(name: 'COMMIT_ID', value: ghprbActualCommit),
            string(name: 'CACHE_CODE_FILESERVER_URL', value: cacheCodeUrl),
            string(name: 'IMAGE', value: image),
            string(name: 'TARGET_BRANCH', value: ghprbTargetBranch),
            text(name: 'COMMON_CMD', value: commonConfig.shellScript),
    ]
    linkBuild(job: "atom-common", parameters: commonParams)
}

node("${GO_BUILD_SLAVE}") {
    container("golang") {
        configs = getConfig(configfile)
        stage("get code") {
            cacheCode()
        }
        jobs = [:]
        for (task in configs.tasks) {
            taskType = task.taskType.toString()
            taskName =task.name.toString()
            switch(taskType) {
                case "build":
                    def buildConfig = parseBuildConfig(task)
                    jobs[taskName] = {
                        buildBinary(buildConfig)
                    }
                    break
                case "unit-test":
                    def unitTestConfig = parseUnitTestConfig(task)
                    jobs[taskName] = {
                        unitTest(unitTestConfig)
                    }
                    break
                case "lint":
                    def lintConfig = parseLintConfig(task)
                    jobs[taskName] = {
                        codeLint(lintConfig)
                    }
                    break
                case "cyclo": 
                    def cycloConfig = parseCycloConfig(task)
                    jobs[taskName] = {
                        codeCyclo(cycloConfig)
                    }
                    break
                case "gosec":
                    def gosecConfig = parseGosecConfig(task)
                    jobs[taskName] = {
                        codeGosec(gosecConfig)
                    }
                    break
                case "common":
                    def commonConfig = parseCommonConfig(task)
                    jobs[taskName] = {
                        codeCommon(commonConfig)
                    }
                    break
            }
        }
        stage("verify") {
            parallel jobs
        }
    }
}
