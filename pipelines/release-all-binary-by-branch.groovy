/*
* @GIT_BRANCH(string:repo branch, Required)
* @FORCE_REBUILD(bool:if force rebuild binary,default true,Optional)
*/

properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'GIT_BRANCH',
                        trim: true
                ),
                booleanParam(
                        defaultValue: false,
                        name: 'FORCE_REBUILD'
                )
        ])
])

def get_sha(repo) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${GIT_BRANCH} -s=${FILE_SERVER_URL}").trim()
}

RELEASE_TAG = "${GIT_BRANCH}-nightly"

def release_one(repo) {
    def sha1 =  get_sha(repo)
    def binary = "builds/pingcap/${repo}/test/${GIT_BRANCH}/${sha1}/linux-arm64/${repo}.tar.gz"
    def paramsBuild = [
        string(name: "ARCH", value: "arm64"),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: binary),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: repo),
        string(name: "GIT_HASH", value: sha1),
        string(name: "RELEASE_TAG", value: RELEASE_TAG),
        string(name: "TARGET_BRANCH", value: GIT_BRANCH),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: false],
    ]
    build job: "build-common",
            wait: true,
            parameters: paramsBuild
}


stage ("release") {
    node("${GO_BUILD_SLAVE}") {
        container("golang") {
            releaseRepos = ["tidb","tikv","dm"]
            builds = [:]
            for (item in releaseRepos) {
                def product = "${item}"
                builds["build ${item}"] = {
                    release_one(product)
                }
            }
            parallel builds
        }
    }
}