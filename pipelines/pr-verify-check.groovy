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




def getConfig(fileURL) {
    sh "wget -qnc ${fileURL}"
    configs = readYaml (file: "verify.yaml")
    return configs
}


def parseBuildConfig(configs) {
    def buildConfig = new BuildConfig()
    buildConfig.outputDir = configs.build.outputDir.toString()
    buildConfig.shellScript = configs.build.shellScript.toString()
    return buildConfig
}

def parseUnitTestConfig(configs) {
    def unitTestConfig = new UnitTestConfig()
    unitTestConfig.utReportDir = configs.unitTest.utReportDir.toString()
    unitTestConfig.covReportDir = configs.unitTest.covReportDir.toString()
    unitTestConfig.shellScript = configs.unitTest.shellScript.toString()
    unitTestConfig.coverageRate = configs.unitTest.coverageRate.toString()
    return unitTestConfig
}

def parseLintConfig(configs) {
    def lintConfig = new LintConfig()
    lintConfig.reportDir = configs.lint.reportDir.toString()
    lintConfig.shellScript = configs.lint.shellScript.toString()
    return lintConfig
}

def parseGosecConfig(configs) {
    def gosecConfig = new GosecConfig()
    gosecConfig.reportDir = configs.gosec.reportDir.toString()
    gosecConfig.shellScript = configs.gosec.shellScript.toString()
    return gosecConfig
}



cacheCodeUrl = "${FILE_SERVER_URL}/download/builds/pingcap/devops/cachecode/${repo}/${ghprbActualCommit}/${repo}.tar.gz"

def cacheCode() {
    cacheCodeParams = [
        string(name: 'REPO', value: repo),
        string(name: 'COMMIT_ID', value: ghprbActualCommit),
        string(name: 'PULL_ID', value: ghprbPullId),
    ]
    build(job: "cache-code", parameters: cacheCodeParams, wait: true)
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
    build(job: "atom-build", parameters: buildParams, wait: true)
}

def codeLint(lintConfig) {
    lintParams = [
        string(name: 'REPO', value: repo),
        string(name: 'COMMIT_ID', value: ghprbActualCommit),
        string(name: 'CACHE_CODE_FILESERVER_URL', value: cacheCodeUrl),
        text(name: 'LINT_CMD', value: lintConfig.shellScript),
        string(name: 'REPORT_DIR', value: lintConfig.reportDir),
    ]
    build(job: "atom-lint", parameters: lintParams, wait: true)
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
    build(job: "atom-ut", parameters: buildParams, wait: true)
}

def codeGosec(gosecConfig) {
    gosecParams = [
            string(name: 'REPO', value: repo),
            string(name: 'COMMIT_ID', value: ghprbActualCommit),
            string(name: 'CACHE_CODE_FILESERVER_URL', value: cacheCodeUrl),
            text(name: 'CMD', value: gosecConfig.shellScript),
            string(name: 'REPORT_DIR', value: gosecConfig.reportDir),
    ]
    build(job: "atom-gosec", parameters: gosecParams, wait: true)
}


node("${GO_BUILD_SLAVE}") {
    container("golang") {
        configs = getConfig(configfile)
        buildConfig = parseBuildConfig(configs)
        unitTestConfig = parseUnitTestConfig(configs)
        lintConfig = parseLintConfig(configs)
        gosecConfig = parseGosecConfig(configs)
        stage("get code") {
            cacheCode()
        }
        jobs = [:]
        jobs["build"] = {
            buildBinary(buildConfig)
        }
        jobs["lint"] = {
            codeLint(lintConfig)
        }
        jobs["uint-test"] = {
            unitTest(unitTestConfig)
        }
        jobs["gosec"] = {
            codeGosec(gosecConfig)
        }
        stage("verify") {
            parallel jobs
        }
    }
}
