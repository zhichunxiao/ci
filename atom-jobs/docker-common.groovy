/*
* @INPUT_BINARY(string:binary url on fileserver, transfer througth atom jobs, Required)
* @PRODUCT(string:product name,eg tidb-binlog, Required)
* @ARCH(enumerate:linux-arm64,linux-amd64,darwin-amd64, Required)
* @GIT_HASH(string:to get correct code from github, Required)
* @RELEASE_TAG(string:for release workflow,what tag to release, Optional)
* @TARGET_BRANCH(string:for daily CI workflow, Optional)
* @RELEASE_DOCKER_IMAGES(string:image to release seprate by comma, Required)
* @DOCKERFILE(string:dockerfile url or file path, Required)
* @EDITION(enumerate:,community,enterprise,Required)
*/
