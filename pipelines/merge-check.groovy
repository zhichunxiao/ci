repo = "" // chanage to origin repo
org = ""
repoInfo = REPO.split("/")
if (repoInfo.length == 2) {
    org = repoInfo[0]
    repo = repoInfo[1]
}
BRANCH = REF
if (REF.startsWith("refs/heads/")) {
    BRANCH = REF.replaceAll("refs/heads/","")
}

def configfile = "https://raw.githubusercontent.com/dianqihanwangzi/devops-config/merge-ci/${repo}/merge.yaml"

try {
    node("${GO_BUILD_SLAVE}") {
        container("golang") {
            checkout scm
            def common = load "pipelines/common.groovy"
            configs = common.getConfig(configfile)

            stage("get code") {
                common.cacheCode(REPO,COMMIT_ID,BRANCH,"")
            }
            jobs = [:]
            for (task in configs.tasks) {
                taskType = task.taskType.toString()
                taskName =task.name.toString()
                switch(taskType) {
                    case "build":
                        def buildConfig = common.parseBuildConfig(task)
                        jobs[taskName] = {
                            common.buildBinary(buildConfig,repo,COMMIT_ID)
                        }
                        break
                    case "unit-test":
                        def unitTestConfig = common.parseUnitTestConfig(task)
                        jobs[taskName] = {
                            common.unitTest(unitTestConfig,repo,COMMIT_ID)
                        }
                        break
                    case "lint":
                        def lintConfig = common.parseLintConfig(task)
                        jobs[taskName] = {
                            common.codeLint(lintConfig,repo,COMMIT_ID)
                        }
                        break
                    case "cyclo": 
                        def cycloConfig = common.parseCycloConfig(task)
                        jobs[taskName] = {
                            common.codeCyclo(cycloConfig,repo,COMMIT_ID)
                        }
                        break
                    case "gosec":
                        def gosecConfig = common.parseGosecConfig(task)
                        jobs[taskName] = {
                            common.codeGosec(gosecConfig,repo,COMMIT_ID)
                        }
                        break
                    case "common":
                        def commonConfig = common.parseCommonConfig(task)
                        jobs[taskName] = {
                            common.codeCommon(commonConfig,repo,COMMIT_ID,BRANCH)
                        }
                        break
                }
            }
            stage("merge ci") {
                parallel jobs
            }
        }
    }
    currentBuild.result = "SUCCESS"
} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
} catch (hudson.AbortException e) {
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
    } else {
        currentBuild.result = "FAILURE"
    }
} catch (InterruptedException e) {
    println e
    currentBuild.result = "ABORTED"
}
catch (Exception e) {
    if (e.getMessage().equals("hasBeenTested")) {
        currentBuild.result = "SUCCESS"
    } else {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
    }
}

// node("github-status-updater") {
//     stage("Update commit status") {
//         container("github-status-updater") {
//             if (currentBuild.result == "ABORTED") {
//                 DESCRIPTION = 'Jenkins job aborted'
//                 // Commit state. Possible values are 'pending', 'success', 'error' or 'failure'
//                 STATUS = 'error'
//             } else if (currentBuild.result == "FAILURE") {
//                 DESCRIPTION = 'Jenkins job failed'
//                 STATUS = 'failure'
//             } else if (currentBuild.result == "SUCCESS") {
//                 DESCRIPTION = 'Jenkins job success'
//                 STATUS = 'success'
//             } else {
//                 DESCRIPTION = 'Jenkins job meets something wrong'
//                 STATUS = 'error'
//             }
//             withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
//                 sh '''
//                 set +x
//                 github-status-updater \
//                     -action update_state \
//                     -token ${TOKEN} \
//                     -owner ${org} \
//                     -repo ${repo} \
//                     -ref  ${COMMIT_ID} \
//                     -state ${STATUS} \
//                     -context ${repo}/merge-ci \
//                     -description "${DESCRIPTION}" \
//                     -url "${RUN_DISPLAY_URL}"
//                 '''
//             }
//         }
//     }
// }
