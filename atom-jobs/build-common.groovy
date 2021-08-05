/*
* @OUTPUT_BINARY(string:binary url on fileserver, transfer througth atom jobs,Required)
* @REPO(string:repo name,eg tidb, Required)
* @PRODUCT(string:product name,eg tidb-ctl,if not set,default was the same as repo name, Optional)
* @ARCH(enumerate:arm64,amd64,Required)
* @OS(enumerate:linux,darwin,Required)
* @GIT_HASH(string:to get correct code from github,Required)
* @GIT_PR(string:generate ref head to pre get code from pr,Optional)
* @RELEASE_TAG(string:for release workflow,what tag to release,Optional)
* @TARGET_BRANCH(string:for daily CI workflow,Optional)
* @FORCE_REBUILD(bool:if force rebuild binary,default true,Optional)
* @EDITION(enumerate:,community,enterprise,Required)
*/

properties([
        parameters([
                choice(
                        choices: ['arm64', 'amd64'],
                        name: 'ARCH'
                ),
                choice(
                        choices: ['linux', 'darwin'],
                        name: 'OS'
                ),
                choice(
                        choices: ['community', 'enterprise'],
                        name: 'EDITION'
                ),
                string(
                        defaultValue: '',
                        name: 'OUTPUT_BINARY',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'REPO',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PRODUCT',
                        trim: true,
                ),
                string(
                        defaultValue: '',
                        name: 'GIT_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'GIT_PR',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'RELEASE_TAG',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TARGET_BRANCH',
                        trim: true
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'FORCE_REBUILD'
                )
        ])
])

if (params.PRODUCT.length() <= 1) {
    PRODUCT = REPO
}

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
def GO_BIN_PATH = "/usr/local/go/bin"
if (needUpgradeGoVersion(params.RELEASE_TAG,params.TARGET_BRANCH)) {
   goBuildPod = "${GO1160_BUILD_SLAVE}"
   GO_BIN_PATH = "/usr/local/go1.16.4/bin"
}

// choose which node to use.
def nodeLabel = goBuildPod
def containerLabel = "golang"
def binPath = ""
if (params.PRODUCT == "tikv" || params.PRODUCT == "importer") {
    nodeLabel = "build"
    containerLabel = "rust"
} 
if (params.PRODUCT == "tics") {
    nodeLabel = "build_tiflash"
    containerLabel = "tiflash"
} 
if (params.ARCH == "arm64") {
    binPath = "/usr/local/node/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin:${GO_BIN_PATH}"
    nodeLabel = "arm"
    containerLabel = ""
    if (params.PRODUCT == "tics"){
        nodeLabel = "tiflash_build_arm"
        containerLabel = "tiflash"
    }
}
if (params.OS == "darwin") {
    binPath = "/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}"
    nodeLabel = "mac"
    containerLabel = ""
}

// define git url and git ref.
repo = "git@github.com:pingcap/${REPO}.git"
if (REPO == "tikv" || REPO == "importer" || REPO == "pd") {
    repo = "git@github.com:tikv/${REPO}.git"
}
specRef = "+refs/heads/*:refs/remotes/origin/*"
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
                                            refspec      : specRef,
                                            url          : repo]]]
    sh "git checkout ${GIT_HASH}"
}


// define build script here.
TARGET = "output" 
buildsh = [:]
buildsh["tidb-ctl"] = """
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go build -o binarys/${PRODUCT}
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp binarys/${PRODUCT} ${TARGET}/bin/            
"""

buildsh["tidb"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [ ${EDITION} == 'enterprise' ]; then
    export TIDB_EDITION=Enterprise
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
make clean
git checkout .
if [ ${OS} == 'linux' ]; then
    WITH_RACE=1 make && mv bin/tidb-server bin/tidb-server-race
    git checkout .
    WITH_CHECK=1 make && mv bin/tidb-server bin/tidb-server-check
    git checkout .
    make failpoint-enable && make server && mv bin/tidb-server{,-failpoint} && make failpoint-disable
    git checkout .
    make server_coverage || true
    git checkout .
    if [ \$(grep -E '^ddltest:' Makefile) ]; then
        git checkout .
        make ddltest
    fi
        
    if [ \$(grep -E '^importer:' Makefile) ]; then
        git checkout .
        make importer
    fi
    make 
else 
    make 
fi;
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp binarys/tidb-ctl ${TARGET}/bin/ || true
cp bin/* ${TARGET}/bin/ 

"""

buildsh["tidb-binlog"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
make clean
git checkout .
make
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["pd"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
git checkout .
if [ ${EDITION} == 'enterprise' ]; then
    export TIDB_EDITION=Enterprise
fi;
make
make tools
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["tidb-tools"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
make clean
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["ticdc"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["br"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["dumpling"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["tidb-enterprise-tools"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
make syncer
make loader
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["tics"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [ ${EDITION} == 'enterprise' ]; then
    export TIFLASH_EDITION=Enterprise
fi;
if [ ${ARCH} == 'amd64' ]; then
    mkdir -p release-darwin/build/
    [ -f "release-darwin/build/build-release.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-release.sh > release-darwin/build/build-release.sh
    [ -f "release-darwin/build/build-cluster-manager.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-cluster-manager.sh > release-darwin/build/build-cluster-manager.sh
    [ -f "release-darwin/build/build-tiflash-proxy.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-tiflash-proxy.sh > release-darwin/build/build-tiflash-proxy.sh
    [ -f "release-darwin/build/build-tiflash-release.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-tiflash-release.sh > release-darwin/build/build-tiflash-release.sh
    chmod +x release-darwin/build/*
    ./release-darwin/build/build-release.sh
    ls -l ./release-darwin/tiflash/
    mv release-darwin ${TARGET}
else
    NPROC=12 release-centos7/build/build-release.sh
    mv release-centos7 ${TARGET}
fi
rm -rf ${TARGET}/build-release || true
"""

buildsh["tikv"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [ ${EDITION} == 'enterprise' ]; then
    export TIKV_EDITION=Enterprise
    export ROCKSDB_SYS_SSE=0
fi;
if [ ${OS} == 'linux' ]; then
    grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
    if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
        echo using gcc 8
        source /opt/rh/devtoolset-8/enable
    fi;
fi;
# if [ ${RELEASE_TAG}x == ''x ];then
#     CARGO_TARGET_DIR=.target ROCKSDB_SYS_STATIC=1 make fail_release && mv bin/tikv-server bin/tikv-server-failpoint 
# fi;
CARGO_TARGET_DIR=.target ROCKSDB_SYS_STATIC=1 make dist_release
wait
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp bin/* ${TARGET}/bin
"""

buildsh["importer"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
ROCKSDB_SYS_SSE=0 make release
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp target/release/tikv-importer ${TARGET}/bin
"""

buildsh["monitoring"] = """
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go build -o pull-monitoring  cmd/monitoring.go
./pull-monitoring  --config=monitoring.yaml --auto-push --tag=${RELEASE_TAG} --token=${env.TOKEN}
rm -rf ${TARGET}
mkdir -p ${TARGET}
mv monitor-snapshot/${RELEASE_TAG}/operator ${TARGET}
"""

buildsh["tidb-test"] = """
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
if [ -d "partition_test/build.sh" ]; then
    cd partition_test
    bash build.sh
    cd ..
fi;
if [ -d "coprocessor_test/build.sh" ]; then
    cd coprocessor_test
    bash build.sh
    cd ..
fi;
if [ -d "concurrent-sql/build.sh" ]; then
    cd concurrent-sql
    bash build.sh
    cd ..
fi;
"""

buildsh["enterprise-plugin"] = """
rsync -av --progress ./ ./enterprise-plugin --exclude enterprise-plugin
git clone https://github.com/pingcap/tidb.git --depth=1
cd tidb/cmd/pluginpkg
go build 
cd ../../..
go mod tidy
tidb/cmd/pluginpkg/pluginpkg -pkg-dir whitelist -out-dir whitelist
md5sum whitelist-1.so > whitelist-1.so.md5
curl -F builds/pingcap/tidb-plugins/test/${RELEASE_TAG}/centos7/whitelist-1.so.md5=@whitelist-1.so.md5 ${FILE_SERVER_URL}/upload
curl -F builds/pingcap/tidb-plugins/test/${RELEASE_TAG}/centos7/whitelist-1.so=@whitelist-1.so ${FILE_SERVER_URL}/upload
go mod tidy
tidb/cmd/pluginpkg/pluginpkg -pkg-dir enterprise-plugin/audit -out-dir enterprise-plugin/audit
md5sum audit-1.so > audit-1.so.md5
curl -F builds/pingcap/tidb-plugins/test/${RELEASE_TAG}/centos7/audit-1.so.md5=@audit-1.so.md5 ${FILE_SERVER_URL}/upload
curl -F builds/pingcap/tidb-plugins/test/${RELEASE_TAG}/centos7/audit-1.so=@audit-1.so ${FILE_SERVER_URL}/upload
rm -rf ${TARGET}
mkdir ${TARGET}/bin
"""

def packageBinary() {
    if ((PRODUCT == "pd" || PRODUCT == "tidb" || PRODUCT == "tidb-test" ) && RELEASE_TAG.length() < 1) {
        sh """
        tar --exclude=${TARGET}.tar.gz -czvf ${TARGET}.tar.gz *
        curl -F ${OUTPUT_BINARY}=@${TARGET}.tar.gz ${FILE_SERVER_URL}/upload
        """
    }else {
        sh """
        cd ${TARGET}
        tar --exclude=${TARGET}.tar.gz -czvf ${TARGET}.tar.gz *
        curl -F ${OUTPUT_BINARY}=@${TARGET}.tar.gz ${FILE_SERVER_URL}/upload
        """
    }
}

def release() {
    // if has built,skip build.
    if (ifFileCacheExists()) {
        return
    }
    checkoutCode()
    // some build need this token.
    withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
        sh buildsh[params.PRODUCT]
    }
    packageBinary()
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
