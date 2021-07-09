/*
* @OUTPUT_BINARY(string:binary url on fileserver, transfer througth atom jobs,Required)
* @PRODUCT(string:product name,eg tidb-binlog,Required)
* @ARCH(enumerate:linux-arm64,linux-amd64,darwin-arm64,Required)
* @GIT_HASH(string:to get correct code from github,Required)
* @GIT_PR(string:generate ref head to pre get code from pr,Optional)
* @RELEASE_TAG(string:for release workflow,what tag to release,Optional)
* @TARGET_BRANCH(string:for daily CI workflow,Optional)
* @FORCE_REBUILD(bool:if force rebuild binary,default true,Optional)
* @EDITION(enumerate:,community,enterprise,Required)
*/


// check if binary already has been built. 

def ifFileCacheExists() {
    if (params.FORCE_REBUILD){
        return false
    } 
    result = sh(script: "curl -I ${FILE_SERVER_URL}/download/${OUTPUT_BINARY} -X \"HEAD\"|grep \"200 OK\"", returnStatus: true)
    // result equal 0 mean cache file exists
    if (result == 0) {
        echo "file ${FILE_SERVER_URL}/download/${OUTPUT_BINARY} found in cache server,skip build again"
        return true
    }
    return false
}
// if has built,skip build.
if ifFileCacheExists() {
    return
}

// choose which go version to use. 
def boolean needUpgradeGoVersion(String tag,String branch) {
    if (tag.startsWith("v") && tag > "v5.1") {
        println "tag=${tag} need upgrade go version"
        return true
    }
    if (branch.startsWith("master") || branch.startsWith("release-5.1")) {
        println "targetBranch=${branch} need upgrade go version"
        return true
    }
    return false
}
def goBuildPod = "${GO_BUILD_SLAVE}"
def goBinPath = "/usr/local/go/bin"
if needUpgradeGoVersion(params.RELEASE_TAG,params.TARGET_BRANCH) {
   goBuildPod = "${GO1160_BUILD_SLAVE}"
   goBinPath = "/usr/local/go/bin"
}

// choose which node to use.
def nodeLabel = goBuildPod
def containerLabel = "golang"
if (params.PRODUCT == "tikv" || params.PRODUCT == "importer") {
    nodeLabel = "build"
    containerLabel = "rust"
} 
if (params.PRODUCT == "tics") {
    nodeLabel = "build_tiflash"
    containerLabel = "tiflash"
} 
if (params.ARCH == "linux-arm64") {
    nodeLabel = "arm"
    containerLabel = ""
    if (params.PRODUCT == "tiflash"){
        nodeLabel = "tiflash_build_arm"
        containerLabel = "tiflash"
    }
}
if (params.ARCH == "darwin-amd64") {
    nodeLabel = "mac"
    containerLabel = ""
    if (params.PRODUCT == "tiflash"){
        nodeLabel = "mac-i5"
        containerLabel = ""
    }
}

// define git url and git ref.
def repo = "git@github.com:pingcap/${PRODUCT}.git"
if (repo == "tikv" || repo == "importer" || repo == "pd") {
    repo = "git@github.com:tikv/${PRODUCT}.git"
}
def specRef = "+refs/heads/*:refs/remotes/origin/*"
if (params.GIT_PR.length() >= 1) {
   specRef = "+refs/pull/${GIT_PR}/*:refs/remotes/origin/pr/${GIT_PR}/*"
}
def checkoutCode() {
    checkout changelog: false, poll: true,
                    scm: [$class: 'GitSCM', branches: [[name: "${GIT_HASH}"]], doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'CheckoutOption', timeout: 30],
                                    [$class: 'CloneOption', timeout: 60],
                                    [$class: 'PruneStaleBranch'],
                                    [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: ''],
                                    [$class: 'CleanBeforeCheckout']], submoduleCfg: [],
                        userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                            refspec      : "${specRef}",
                                            url          : "${repo}"]]]
}


// define build script here.
def TARGET = "output" 
def buildsh = [:]
buildsh["tidb-ctl"] = """
if [ ${ARCH} != "linux-amd64" ]; then
    export PATH=${GO_BIN_PATH}:$PATH
fi;
go build -o binarys/${PRODUCT}
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp binarys/${PRODUCT} ${TARGET}/bin/            
"""

buildsh["tidb"] = """
for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
git tag -f ${RELEASE_TAG} ${GIT_HASH}
git branch -D refs/tags/${RELEASE_TAG} || true
git checkout -b refs/tags/${RELEASE_TAG}
if [ ${ARCH} != "linux-amd64" ]; then
    export PATH=${GO_BIN_PATH}:$PATH
fi;
make clean
git checkout .

if [ ${EDITION} == "enterprise"]; then
    export TIDB_EDITION=Enterprise
fi;
make
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp binarys/tidb-ctl ${TARGET}/bin/
cp bin/* ${TARGET}/bin/   
"""

buildsh["tidb-binlog"] = """
for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
git tag -f ${RELEASE_TAG} ${GIT_HASH}
git branch -D refs/tags/${RELEASE_TAG} || true
git checkout -b refs/tags/${RELEASE_TAG}
if [ ${ARCH} != "linux-amd64" ]; then
    export PATH=${GO_BIN_PATH}:$PATH
fi;
make clean
git checkout .
make
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["pd"] = """
for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
git tag -f ${RELEASE_TAG} ${GIT_HASH}
git branch -D refs/tags/${RELEASE_TAG} || true
git checkout -b refs/tags/${RELEASE_TAG}
if [ ${ARCH} != "linux-amd64" ]; then
    export PATH=${GO_BIN_PATH}:$PATH
fi;
git checkout .
if [ ${EDITION} == "enterprise"]; then
    export TIDB_EDITION=Enterprise
fi;
make
make tools
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["tidb-tools"] = """
for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
git tag -f ${RELEASE_TAG} ${GIT_HASH}
git branch -D refs/tags/${RELEASE_TAG} || true
git checkout -b refs/tags/${RELEASE_TAG}
if [ ${ARCH} != "linux-amd64" ]; then
    export PATH=${GO_BIN_PATH}:$PATH
fi;
make clean
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["ticdc"] = """
for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
git tag -f ${RELEASE_TAG} ${GIT_HASH}
git branch -D refs/tags/${RELEASE_TAG} || true
git checkout -b refs/tags/${RELEASE_TAG}
if [ ${ARCH} != "linux-amd64" ]; then
    export PATH=${GO_BIN_PATH}:$PATH
fi;
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["br"] = """
for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
git tag -f ${RELEASE_TAG} ${GIT_HASH}
git branch -D refs/tags/${RELEASE_TAG} || true
git checkout -b refs/tags/${RELEASE_TAG}
if [ ${ARCH} != "linux-amd64" ]; then
    export PATH=${GO_BIN_PATH}:$PATH
fi;
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["dumpling"] = """
for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
git tag -f ${RELEASE_TAG} ${GIT_HASH}
git branch -D refs/tags/${RELEASE_TAG} || true
git checkout -b refs/tags/${RELEASE_TAG}
if [ ${ARCH} != "linux-amd64" ]; then
    export PATH=${GO_BIN_PATH}:$PATH
fi;
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["tics"] = """
for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
git tag -f ${RELEASE_TAG} ${GIT_HASH}
git branch -D refs/tags/${RELEASE_TAG} || true
git checkout -b refs/tags/${RELEASE_TAG}
if [ ${EDITION} == "enterprise"]; then
    export TIFLASH_EDITION=Enterprise
fi;
if [ ${ARCH} == "darwin-amd64" ]; then
    mkdir -p release-darwin/build/
    [ -f "release-darwin/build/build-release.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-release.sh > release-darwin/build/build-release.sh
    [ -f "release-darwin/build/build-cluster-manager.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-cluster-manager.sh > release-darwin/build/build-cluster-manager.sh
    [ -f "release-darwin/build/build-tiflash-proxy.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-tiflash-proxy.sh > release-darwin/build/build-tiflash-proxy.sh
    [ -f "release-darwin/build/build-tiflash-release.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-tiflash-release.sh > release-darwin/build/build-tiflash-release.sh
    chmod +x release-darwin/build/*
    ./release-darwin/build/build-release.sh
    ls -l ./release-darwin/tiflash/
    cd release-darwin/
else
    NPROC=12 release-centos7/build/build-release.sh
    cd release-centos7/
fi
mv tiflash ${TARGET}
"""

buildsh["tikv"] = """
for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
git tag -f ${RELEASE_TAG} ${GIT_HASH}
git branch -D refs/tags/${RELEASE_TAG} || true
git checkout -b refs/tags/${RELEASE_TAG}
if [ ${EDITION} == "enterprise"]; then
    TIKV_EDITION=Enterprise CARGO_TARGET_DIR=.target ROCKSDB_SYS_STATIC=1 ROCKSDB_SYS_SSE=0 make dist_release
else;
    CARGO_TARGET_DIR=.target ROCKSDB_SYS_STATIC=1 make dist_release
fi;
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp bin/* binarys
cp bin/* ${TARGET}/bin
"""

buildsh["importer"] = """
for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
git tag -f ${RELEASE_TAG} ${GIT_HASH}
git branch -D refs/tags/${RELEASE_TAG} || true
git checkout -b refs/tags/${RELEASE_TAG}
ROCKSDB_SYS_SSE=0 make release
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp target/release/tikv-importer ${TARGET}/bin
"""

def package() {
    sh """
    tar --exclude=${TARGET}.tar.gz -czvf ${TARGET}.tar.gz ${TARGET}
    curl -F ${OUTPUT_BINARY}=@${TARGET}.tar.gz ${FILE_SERVER_URL}/upload
    """
}

def release() {
    checkoutCode()
    sh buildsh[params.PRODUCT]
    package()
}


stage("Build ${PRODUCT}") {
    node(nodeLabel) {
        dir("go/src/github.com/pingcap/${PRODUCT}") {
            if (containerLabel != "") {
                container(containerLabel){
                    release()
                }
            }else {
                release()
            }
        }
    }
}
