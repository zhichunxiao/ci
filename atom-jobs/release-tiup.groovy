/*
* @INPUT_BINARY(string:binary url on fileserver, transfer througth atom jobs, Required)
* @REPO(string:repo name,eg tidb, Required)
* @PRODUCT(string:product name,eg tidb-binlog, Required)
* @ARCH(enumerate:linux-arm64,linux-amd64,darwin-amd64, Required)
* @GIT_HASH(string:to get correct code from github, Required)
* @RELEASE_TAG(string:for release workflow,what tag to release, Optional)
* @EDITION(enumerate:,community,enterprise,Required)
* @TIUP_MIRROR(string:tiup mirror default is "http://172.16.5.139:8988", Optional)
*/