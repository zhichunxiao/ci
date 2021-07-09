/*
* @PRODUCT(string:product name,eg tidb-binlog, Required)
* @GIT_BRANCH(string:repo branch, Required)
* @ARCH(enumerate:linux-arm64,linux-amd64,darwin-arm64,Required)
* @RELEASE_TAG(string:for release workflow,what tag to release, Required)
* @FORCE_REBUILD(bool:if force rebuild binary,default true,Optional)
* @TIUP_MIRROR(string:tiup mirror default is "http://172.16.5.139:8988", Optional)
* @EDITION(enumerate:,community,enterprise,Required)
*/

def get_sha() {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${hash_or_branch} -s=${FILE_SERVER_URL}").trim()
}

def GIT_HASH=""

stage("get hash") {
    node {
       GIT_HASH = get_sha()
    }
}

def BINARY_PATH = "builds/pingcap/${PRODUCT}/test/${RELEASE_TAG}/${GIT_HASH}/${ARCH}/${PRODUCT}.tar.gz"

stage("build") {
    def params = [
        string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
        string(name: "GIT_HASH", value: "${GIT_HASH}"),
        string(name: "PRODUCT", value: "${PRODUCT}"),
        string(name: "ARCH", value: "${ARCH}"),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
        string(name: "EDITION", value: "${EDITION}"),
        string(name: "OUTPUT_BINARY", value: "${BINARY_PATH}"),

    ]
    build job: "build-common",
            wait: true,
            parameters: params
}

stage("release tiup") {
    def params = [
        string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
        string(name: "GIT_HASH", value: "${GIT_HASH}"),
        string(name: "PRODUCT", value: "${PRODUCT}"),
        string(name: "ARCH", value: "linux-amd64"),
        string(name: "EDITION", value: "${EDITION}"),
        string(name: "INPUT_BINARY", value: "${BINARY_PATH}"),
    ]
    build job: "release-tiup",
            wait: true,
            parameters: params
}
