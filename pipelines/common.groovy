class Resource {
   String cpu;
   String memory;
}

class Env {
   String image;
   Resource limit;
   Resource request;
}

class SecretVar {
    String secretID;
    String key;
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
    SecretVar[] secretVars;
    Env env;
}

class jenkinsITConfig {
    String triggerJobName;
    SecretVar[] secretVars;
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
    SecretVar[] secretVars;
}


def getConfig(fileURL) {
    sh "wget -qnc ${fileURL} -O config.yaml"
    configs = readYaml (file: "config.yaml")
    return configs
}

def parseBuildEnv(buildEnv) {
    env = new Env()
    env.image = buildEnv.image.toString()
    return env
}

def parseSecretVars(secretVars) {
    vars = []
    for (secretVar in secretVars) {
        var = new SecretVar()
        var.secretID = secretVar.secretID.toString()
        var.key = secretVar.key.toString()
        vars.push(var)
    }
    return vars
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
    unitTestConfig.secretVars = parseSecretVars(config.secretVar)
    return unitTestConfig
}

def parseJenkinsITConfig(config) {
    def jenkinsITConfig = new jenkinsITConfig()
    jenkinsITConfig.triggerJobName = config.name.toString()
    jenkinsITConfig.secretVars = parseSecretVars(config.secretVar)
    return jenkinsITConfig
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
    commonConfig.env = parseBuildEnv(config.buildEnv)
    commonConfig.secretVars = parseSecretVars(config.secretVar)
    return commonConfig
}


def triggerTask(taskName,params) {
    result = build(job: taskName, parameters: params, wait: true,propagate: false)

    if (result.getResult() != "SUCCESS" && taskName in ["atom-ut", "atom-gosec"]) {
        println("Detail: ${CI_JENKINS_BASE_URL}/blue/organizations/jenkins/${result.getFullProjectName()}/detail/${result.getFullProjectName()}/${result.getNumber().toString()}/tests")
    } else {
        println("Detail: ${CI_JENKINS_BASE_URL}/blue/organizations/jenkins/${result.getFullProjectName()}/detail/${result.getFullProjectName()}/${result.getNumber().toString()}/pipeline")
    }
    if (result.getDescription() != null && result.getDescription() != "") {
        println("task ${result.getResult()}: ${result.getDescription()}")
    } else {
        println("task ${result.getResult()}")
    }

    return result
}


def cacheCode(repo,commitID,branch,prID) {
    cacheCodeParams = [
        string(name: 'ORG_AND_REPO', value: repo),
        string(name: 'COMMIT_ID', value: commitID),
    ]
    if (branch != "" && branch != null ) {
        cacheCodeParams.push(string(name: 'BRANCH', value: branch))
    }
    if (prID != "" && prID != null ) {
        cacheCodeParams.push(string(name: 'PULL_ID', value: prID))
    }
    triggerTask("cache-code",cacheCodeParams)
}

def buildBinary(buildConfig,repo,commitID,branch,taskName,triggerEvent) {
    def cacheCodeUrl = "${FILE_SERVER_URL}/download/builds/pingcap/devops/cachecode/${repo}/${commitID}/${repo}.tar.gz"
    buildParams = [
        string(name: 'REPO', value: repo),
        string(name: 'CACHE_CODE_FILESERVER_URL', value: cacheCodeUrl),
        string(name: 'COMMIT_ID', value: commitID),
        text(name: 'BUILD_CMD', value: buildConfig.shellScript),
        string(name: 'BUILD_ENV', value: "hub-new.pingcap.net/jenkins/centos7_golang-1.16"),
        string(name: 'OUTPUT_DIR', value: "bin"),
        string(name: 'BRANCH', value: branch),
        string(name: 'TASK_NAME', value: taskName),
        string(name: 'TRIGGER_EVENT', value: triggerEvent),
    ]
    triggerTask("atom-build",buildParams)
}

def codeLint(lintConfig,repo, commitID,branch,taskName,triggerEvent) {
    def cacheCodeUrl = "${FILE_SERVER_URL}/download/builds/pingcap/devops/cachecode/${repo}/${commitID}/${repo}.tar.gz"
    lintParams = [
        string(name: 'REPO', value: repo),
        string(name: 'COMMIT_ID', value: commitID),
        string(name: 'CACHE_CODE_FILESERVER_URL', value: cacheCodeUrl),
        text(name: 'LINT_CMD', value: lintConfig.shellScript),
        string(name: 'REPORT_DIR', value: lintConfig.reportDir),
        string(name: 'BRANCH', value: branch),
        string(name: 'TASK_NAME', value: taskName),
        string(name: 'TRIGGER_EVENT', value: triggerEvent),
    ]
    triggerTask("atom-lint",lintParams)
}

def unitTest(unitTestConfig,repo,commitID,branch,taskName,triggerEvent) {
    def cacheCodeUrl = "${FILE_SERVER_URL}/download/builds/pingcap/devops/cachecode/${repo}/${commitID}/${repo}.tar.gz"
    def secretVars = []
    def shellScript = unitTestConfig.shellScript
    for (sVar in unitTestConfig.secretVars) {
        secretVars.push(sVar.secretID + ":" + sVar.key)
        shellScript = shellScript.replace("\${" + sVar.key + "}" , "\$" + sVar.key) 
    }
    def secretVarsString = secretVars.join(",")
    utParams = [
        string(name: 'REPO', value: repo),
        string(name: 'COMMIT_ID', value: commitID),
        string(name: 'CACHE_CODE_FILESERVER_URL', value: cacheCodeUrl),
        text(name: 'TEST_CMD', value: shellScript),
        string(name: 'UT_REPORT_DIR', value: unitTestConfig.utReportDir),
        string(name: 'COV_REPORT_DIR', value: unitTestConfig.covReportDir),
        string(name: 'COVERAGE_RATE', value: unitTestConfig.coverageRate),
        string(name: 'TEST_ENV', value: "hub-new.pingcap.net/jenkins/centos7_golang-1.16"),
        string(name: 'BRANCH', value: branch),
        string(name: 'TASK_NAME', value: taskName),
        string(name: 'TRIGGER_EVENT', value: triggerEvent),
        string(name: 'SECRET_VARS', value: secretVarsString),
    ]
    triggerTask("atom-ut",utParams)
}

def codeGosec(gosecConfig,repo,commitID,branch,taskName,triggerEvent) {
    def cacheCodeUrl = "${FILE_SERVER_URL}/download/builds/pingcap/devops/cachecode/${repo}/${commitID}/${repo}.tar.gz"
    gosecParams = [
            string(name: 'REPO', value: repo),
            string(name: 'COMMIT_ID', value: commitID),
            string(name: 'CACHE_CODE_FILESERVER_URL', value: cacheCodeUrl),
            text(name: 'CMD', value: gosecConfig.shellScript),
            string(name: 'REPORT_DIR', value: gosecConfig.reportDir),
            string(name: 'BRANCH', value: branch),
            string(name: 'TASK_NAME', value: taskName),
            string(name: 'TRIGGER_EVENT', value: triggerEvent),
    ]
    triggerTask("atom-gosec",gosecParams)
}

def codeCyclo(cycloConfig,repo,commitID,branch,taskName,triggerEvent) {
    def cacheCodeUrl = "${FILE_SERVER_URL}/download/builds/pingcap/devops/cachecode/${repo}/${commitID}/${repo}.tar.gz"
    cycloParams = [
            string(name: 'REPO', value: repo),
            string(name: 'COMMIT_ID', value: commitID),
            string(name: 'CACHE_CODE_FILESERVER_URL', value: cacheCodeUrl),
            text(name: 'CYCLO_CMD', value: cycloConfig.shellScript),
            string(name: 'BRANCH', value: branch),
            string(name: 'TASK_NAME', value: taskName),
            string(name: 'TRIGGER_EVENT', value: triggerEvent),
    ]
    triggerTask("atom-cyclo",cycloParams)
}


def jenkinsItTrigger(jenkinsITConfig,taskName,ghprbTargetBranch,ghprbActualCommit,triggerEvent) {
    jenkinsItTriggerParams = [
            string(name: 'TRIGGER_JOB_NAME', value: jenkinsITConfig.triggerJobName),
            string(name: 'GHPRB_TARGET_BRANCH', value: ghprbTargetBranch),
            string(name: 'GHPRB_ACTUAL_COMMIT', value: ghprbActualCommit),
            string(name: 'TASK_NAME', value: taskName),
            string(name: 'TRIGGER_EVENT', value: triggerEvent),
    ]
    triggerTask("atom-jenkins-it-trigger",jenkinsItTriggerParams)
}

def codeCommon(commonConfig,repo,commitID,branch,taskName,triggerEvent) {
    def cacheCodeUrl = "${FILE_SERVER_URL}/download/builds/pingcap/devops/cachecode/${repo}/${commitID}/${repo}.tar.gz"
    def image = "hub-new.pingcap.net/jenkins/centos7_golang-1.16"
    if (commonConfig.env.image != null && commonConfig.env.image != "") {
        image = commonConfig.env.image
    }
    secretVars = []
    def script = commonConfig.shellScript
    for (sVar in commonConfig.secretVars) {
        secretVars.push(sVar.secretID + ":" + sVar.key)
        script = script.replace("\${" + sVar.key + "}" , "\$" + sVar.key) 
    }
    secretVarsString = secretVars.join(",")
    commonParams = [
            string(name: 'REPO', value: repo),
            string(name: 'COMMIT_ID', value: commitID),
            string(name: 'CACHE_CODE_FILESERVER_URL', value: cacheCodeUrl),
            string(name: 'IMAGE', value: image),
            string(name: 'TARGET_BRANCH', value: branch),
            string(name: 'SECRET_VARS', value: secretVarsString),
            text(name: 'COMMON_CMD', value: script),
            string(name: 'BRANCH', value: branch),
            string(name: 'TASK_NAME', value: taskName),
            string(name: 'TRIGGER_EVENT', value: triggerEvent),
    ]
    triggerTask("atom-common",commonParams)
}
return this