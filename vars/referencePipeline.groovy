def call(body) {
    def pipelineParams = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {
	agent {
	    label 'master'
	}
	stages {
	    stage('Test') {
		agent {
		    docker {
				image 'cloudfluff/csvlint'
				reuseNode true
				alwaysPull true
		    }
		}
		steps {
		    script {
			ansiColor('xterm') {
			    sh "csvlint -s codelists-metadata.json"
			    sh "csvlint columns.csv"
			    sh "csvlint components.csv"
			}
		    }
		}
	    }
	    stage('Upload draftset') {
		steps {
		    script {
			jobDraft.replace()
			def codelists = readJSON(file: 'codelists-metadata.json')
			for (def table : codelists['tables']) {
			    String codelistFilename = table['url']
			    String label = table['rdfs:label']
			    uploadCodelist(codelistFilename, label)
			}
			uploadComponents("components.csv")
		    }
		}
	    }
	    stage('Publish') {
		steps {
		    script {
			jobDraft.publish()
		    }
		}
	    }
	}
	post {
	    success {
		build job: '../GDP-tests', wait: false
	    }
	    always {
		step([$class: 'GitHubIssueNotifier',
		  issueAppend: true,
		  issueLabel: '',
		  issueTitle: '$JOB_NAME $BUILD_DISPLAY_NAME failed'])
	    }
	}
    }
}