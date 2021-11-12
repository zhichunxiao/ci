/*
* @GIT_BRANCH(string:repo branch, Required)
* @FORCE_REBUILD(bool:if force rebuild binary,default true,Optional)
*/

properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'RELEASE_BRANCH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'RELEASE_TAG',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIKV_BUMPVERION_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIKV_BUMPVERSION_PRID',
                        trim: true
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'FORCE_REBUILD'
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'NEED_DEBUG_IMAGE'
                )
        ])
])



def get_sha(repo,branch) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${branch} -s=${FILE_SERVER_URL}").trim()
}

IMAGE_TAG = RELEASE_TAG + "-pre"

def release_one(repo,failpoint) {
    def actualRepo = repo
    if (repo == "br" && RELEASE_TAG >= "v5.2.0") {
        actualRepo = "tidb"
    }
    if (repo == "dumpling" && RELEASE_TAG >= "v5.3.0") {
        actualRepo = "tidb"
    }
    def sha1 = get_sha(actualRepo,RELEASE_BRANCH)
    if (TIKV_BUMPVERION_HASH.length() > 1 && repo == "tikv") {
        sha1 = TIKV_BUMPVERION_HASH
    }
    if (repo == "monitoring") {
        sha1 =  get_sha(actualRepo,RELEASE_BRANCH)
    }
    def binary = "builds/pingcap/${repo}/test/${RELEASE_TAG}/${sha1}/linux-amd64/${repo}.tar.gz"
    if (failpoint) {
        binary = "builds/pingcap/${repo}/test/failpoint/${RELEASE_TAG}/${sha1}/linux-amd64/${repo}.tar.gz"
    }
    def paramsBuild = [
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: binary),
        string(name: "REPO", value: actualRepo),
        string(name: "PRODUCT", value: repo),
        string(name: "GIT_HASH", value: sha1),
        string(name: "RELEASE_TAG", value: RELEASE_TAG),
        string(name: "TARGET_BRANCH", value: RELEASE_BRANCH),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
    ]
    if (failpoint) {
        paramsBuild.push([$class: 'BooleanParameterValue', name: 'FAILPOINT', value: true])
    }
    if (TIKV_BUMPVERSION_PRID.length() > 1 && repo == "tikv") {
        paramsBuild.push([$class: 'StringParameterValue', name: 'GIT_PR', value: TIKV_BUMPVERSION_PRID])
    }
    if (repo == "monitoring") {
        paramsBuild.push([$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_BRANCH])
    }
    build job: "build-common",
            wait: true,
            parameters: paramsBuild

    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/${repo}"
    def image = "hub.pingcap.net/qa/${repo}:${IMAGE_TAG},pingcap/${repo}:${IMAGE_TAG}"
    if (repo == "tics") {
        image = image + ",hub.pingcap.net/qa/tiflash:${IMAGE_TAG},pingcap/tiflash:${IMAGE_TAG}"
    }
    if (repo == "monitoring") {
        image = "hub.pingcap.net/qa/tidb-monitor-initializer:${IMAGE_TAG},pingcap/tidb-monitor-initializer:${IMAGE_TAG}"
    }
    if (failpoint) {
        image = "hub.pingcap.net/qa/${repo}:${IMAGE_TAG}-failpoint"
    }
    def paramsDocker = [
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "INPUT_BINARYS", value: binary),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: repo),
        string(name: "RELEASE_TAG", value: RELEASE_TAG),
        string(name: "DOCKERFILE", value: dockerfile),
        string(name: "RELEASE_DOCKER_IMAGES", value: image),
    ]
    build job: "docker-common",
            wait: true,
            parameters: paramsDocker


    if (NEED_DEBUG_IMAGE) {
        def dockerfileForDebug = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/debug-image/${repo}"
        def imageForDebug = "hub.pingcap.net/qa/${repo}:${IMAGE_TAG}-debug"
        if (failpoint) {
            imageForDebug = "hub.pingcap.net/qa/${repo}:${IMAGE_TAG}-failpoint-debug"
        }
        def paramsDockerForDebug = [
            string(name: "ARCH", value: "amd64"),
            string(name: "OS", value: "linux"),
            string(name: "INPUT_BINARYS", value: binary),
            string(name: "REPO", value: repo),
            string(name: "PRODUCT", value: repo),
            string(name: "RELEASE_TAG", value: RELEASE_TAG),
            string(name: "DOCKERFILE", value: dockerfileForDebug),
            string(name: "RELEASE_DOCKER_IMAGES", value: imageForDebug),
        ]
        if (repo in ["dumpling","ticdc","tidb-binlog","tidb","tikv","pd"]) {
            build job: "docker-common",
                    wait: true,
                    parameters: paramsDockerForDebug
        }
    }
    

    if (repo == "br") {
        def dockerfileLightning = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/lightning"
        def imageLightling = "hub.pingcap.net/qa/tidb-lightning:${IMAGE_TAG},pingcap/tidb-lightning:${IMAGE_TAG}"
        def paramsDockerLightning = [
            string(name: "ARCH", value: "amd64"),
            string(name: "OS", value: "linux"),
            string(name: "INPUT_BINARYS", value: binary),
            string(name: "REPO", value: "lightning"),
            string(name: "PRODUCT", value: "lightning"),
            string(name: "RELEASE_TAG", value: RELEASE_TAG),
            string(name: "DOCKERFILE", value: dockerfileLightning),
            string(name: "RELEASE_DOCKER_IMAGES", value: imageLightling),
        ]
        build job: "docker-common",
                wait: true,
                parameters: paramsDockerLightning
        }

        if (NEED_DEBUG_IMAGE) {
            def dockerfileLightningForDebug = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/debug-image/lightning"
            def imageLightlingForDebug = "hub.pingcap.net/qa/tidb-lightning:${IMAGE_TAG}-debug"
            def paramsDockerLightningForDebug = [
                string(name: "ARCH", value: "amd64"),
                string(name: "OS", value: "linux"),
                string(name: "INPUT_BINARYS", value: binary),
                string(name: "REPO", value: "lightning"),
                string(name: "PRODUCT", value: "lightning"),
                string(name: "RELEASE_TAG", value: RELEASE_TAG),
                string(name: "DOCKERFILE", value: dockerfileLightningForDebug),
                string(name: "RELEASE_DOCKER_IMAGES", value: imageLightlingForDebug),
            ]
            build job: "docker-common",
                    wait: true,
                    parameters: paramsDockerLightningForDebug
        }

        
}

stage ("release") {
    node("${GO_BUILD_SLAVE}") {
        container("golang") {
            releaseRepos = ["dumpling","br","ticdc","tidb-binlog","tics","tidb","tikv","pd","monitoring"]
            builds = [:]
            for (item in releaseRepos) {
                def product = "${item}"
                builds["build ${item}"] = {
                    release_one(product,false)
                }
            }
            failpointRepos = ["tidb","pd","tikv"]
            for (item in failpointRepos) {
                def product = "${item}"
                builds["build ${item} failpoint"] = {
                    release_one(product,true)
                }
            }
            parallel builds
        }
    }
}

