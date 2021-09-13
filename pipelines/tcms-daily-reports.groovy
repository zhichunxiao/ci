properties([
    pipelineTriggers([cron('H 7 * * *')]),
])

tcmsHost = "https://tcms.pingcap.net/"

def getStartOfLastDayTimeStamp() {
    Calendar calendar = Calendar.getInstance()
    int year = calendar.get(Calendar.YEAR)
    int month = calendar.get(Calendar.MONTH)
    int day = calendar.get(Calendar.DATE)
    calendar.set(year, month, day-1, 0, 0, 0)
    long approximateTimestamp = calendar.getTime().getTime()
    ts = approximateTimestamp/1000
    tsString = ts.intValue().toString()
    return tsString
}

def response = httpRequest tcmsHost + "api/v1/plans/branchs"
def branches = readJSON text: response.content
for (b in branches) {
    branch= b.toString()
    timestamp = getStartOfLastDayTimeStamp()
    node(GO_BUILD_SLAVE){
        container("golang"){
            stage("branch: "+ branch + " daily ci result") {
                sh """
                curl -o ${branch}.xml ${tcmsHost}api/v1/dailyci?started_at=${timestamp}&branch=${branch}
                """
                junit testResults: "${branch}.xml"
            }
        }
    }
}
