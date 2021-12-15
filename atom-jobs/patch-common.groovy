properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'INPUT_BINARYS',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'BINARY_NAME',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PRODUCT',
                        trim: true,
                ),
                string(
                        defaultValue: '',
                        name: 'PATCH_PATH',
                        trim: true,
                )
        ])
])

def nodeLabel = "${GO1160_BUILD_SLAVE}"
def containerLabel = "golang"
def TARGET = params.BINARY_NAME

// download binarys
binarys = params.INPUT_BINARYS.split(",")
def download() {
    for (item in binarys) {
        sh "curl ${FILE_SERVER_URL}/download/${item} | tar xz"
    }
}

def packageBinary() {
    
    sh """
    rm -rf ${TARGET}
    cp bin/${TARGET} ${TARGET}
    tar -czvf ${TARGET}.tar.gz ${TARGET}
    curl -F ${OUTPUT_BINARY}=@${TARGET}.tar.gz ${FILE_SERVER_URL}/upload
    """
}

stage("Build Patch ${PRODUCT}") {
    node(nodeLabel) {
        dir("go/src/github.com/pingcap/${PRODUCT}") {
            deleteDir()
            container(containerLabel){
                download()
                packageBinary()
            }
        }
    }
}