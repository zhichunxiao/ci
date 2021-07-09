/*
* @GIT_BRANCH(string:repo branch, Required)
* @RELEASE_TAG(string:for release workflow,what tag to release, Required)
* @FORCE_REBUILD(bool:if force rebuild binary,default true,Optional)
* @TIUP_MIRROR(string:tiup mirror default is "http://172.16.5.139:8988", Optional)
* @EDITION(enumerate:,community,enterprise,Required)
*/


def release_one(arch,product) {
    def params = [
        string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
        string(name: "PRODUCT", value: "${product}"),
        string(name: "ARCH", value: "${arch}"),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
        string(name: "EDITION", value: "${EDITION}"),
    ]
    build job: "build-common",
            wait: true,
            parameters: params
}

stage ("release") {
    releaseRepos = ["tidb","tikv","pd","tidb-binlog"]
    builds = [:]
    for (item in releaseRepos) {
        builds["build & release tiup "+ item + "linux-amd64"] = release_one("linux-amd64",item)
        builds["build & release tiup "+ item + "linux-arm64"] = release_one("linux-arm64",item)
        builds["build & release tiup "+ item + "darwin-amd64"] = release_one("darwin-amd64",item)
    }
    parallel builds
}