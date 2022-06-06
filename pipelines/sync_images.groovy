// Script transferred

pipeline {
    agent {label 'jenkins-image-syncer'}

    stages {
        stage('Login to DockerHub and Harbor') {
            steps {
                script {
                    withCredentials([file(credentialsId: 'gcr-registry-key', variable: 'GCR_KEY')]) {
                        sh """
                        cp \$GCR_KEY keyfile.json
                        cat keyfile.json | docker login -u _json_key --password-stdin https://gcr.io
                        """
                    }

                    withCredentials([usernamePassword(credentialsId: 'hub.docker.com-pingcap', usernameVariable: 'username', passwordVariable: 'password')]) {
                        sh """
                        echo "${password}" | docker login --username "pingcap" --password-stdin
                        """
                    }

                    withCredentials([usernamePassword(credentialsId: 'harbor-pingcap', usernameVariable: 'username', passwordVariable: 'password')]) {
                        sh """
                        docker login -u ${harborUser} -p ${harborPassword} hub.pingcap.net
                        """
                    }
            }
        }
        }
       
        stage('Prepare regctl Image') {
            steps {
                sh """
                wget -q http://fileserver.pingcap.net/download/github-actions/regctl-linux-amd64
                chmod +x regctl-linux-amd64
                """
            }
        }


        stage('Sync Image') {

            steps {
	        script {

	            def component = "br"
                    def cmd = "curl -L -s https://registry.hub.docker.com/v1/repositories/pingcap/$component/tags | json_reformat | grep -i name | awk '{print \$2}' | sed 's/\"//g' | sort -u"
                    def tags = sh(returnStdout: true, script: "$cmd").trim()
                    println "$tags"
                    
		    for (tag in "$tags") {
		        def SOURCE_IMAGE = "${SOURCE_IMAGE}" + tag
			def TARGET_IMAGE = "${TARGET_IMAGE}" + tag
			sh """
                        ./regctl-linux-amd64 image copy ${SOURCE_IMAGE} ${TARGET_IMAGE} -v info
                        """
		    }
		}
            }
        }
    }
}
