
## Atom jenkins Job
- 原子任务之间通过文件服务器实现文件或目录共享。
- 所有原子任务粒度为 product ，每一个 git repo 可以有多个product。
- 原子任务对执行过程（构建脚本等）进行封装，避免脚本多处维护。
- 原子任务不暴露给用户使用，用户不感知原子任务复杂性，实现尽量覆盖所有细节。
- 原子任务命名尽量采用 ${action}-${scope} 的模式。

#### build-common
输入:
| name | type | usage | example | required | remark|
| :------: | :------: | :------: | :------: | :------: | :------: |
| REPO | string |github repo name | tikv | true | |
| PRODUCT | string | product name| tikv | true | one repo may have multiple product |
| ARCH | choice | arch name | arm64/amd64 | true |  |
| OS | choice | os name | linux/darwin | true | |
| GIT_HASH | string | github code commit id | 4d5773c298cb51c587f34522d2781b3966bb4742 |  true|  |
| GIT_PR | string | specify the github pull request number | 26888 | false |  |
| RELEASE_TAG | string | release tag | v5.1.1 | false  | only for release binary |
| TARGET_BRANCH | string | target branch to build | master | false | if RELEASE_TAG not set,must set TARGET_BRANCH |
| FORCE_REBUILD | bool | whether rebuild this binary if exist on fileserver | false | false |  default is false |
| EDITION | choice | community or enterprise |community/enterprise | true | |

输出:
| name | type | usage | example | required | remark|
| :------: | :------: | :------: | :------: | :------: | :------: |
| OUTPUT_BINARY | string | output binary url on fileserver  | builds/pingcap/test/br/fa9e52051645d8b4319f3d2daf44e425469171a9/centos7/br-linux-arm64.tar.gz | true | one product only have one binary |


#### docker-common
输入:
| name | type | usage | example | required | remark|
| :------: | :------: | :------: | :------: | :------: | :------: |
| REPO | string |github repo name | tikv | true | |
| PRODUCT | string | product name| tikv | true | one repo may have multiple product |
| INPUT_BINARYS | string | output binary url on fileserver，  | builds/pingcap/test/br/fa9e52051645d8b4319f3d2daf44e425469171a9/centos7/br-linux-arm64.tar.gz | true | may have multiple binaries, separate by comma |
| ARCH | choice | arch name | arm64/amd64 | true |  |
| OS | choice | os name | linux/darwin | true | |
| DOCKERFILE | string | specify dockerfile download url | https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/lightning | false | dockerfile may exsist in input tar ball,so it's not required |
| RELEASE_TAG | string | release tag | v5.1.1 | false  | only for release binary |

输出:
| name | type | usage | example | required | remark|
| :------: | :------: | :------: | :------: | :------: | :------: |
| RELEASE_DOCKER_IMAGES | string | docker images  to release  | pingcap/tidb:v5.1.1 | true | this job only build one image but can release to different repo ,images separate by comma |