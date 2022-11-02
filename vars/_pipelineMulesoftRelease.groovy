def call(AGENT_LABEL, PROJECT, TAG, BRANCH) {
	pipeline {

        agent {
            label AGENT_LABEL
        }

        //(Optional): Define input parameters for pipeline
        // parameters {
        //     //Parameters
        // }
        
        options {
            ansiColor('xterm')
            disableConcurrentBuilds()
            timeout(time: 1, unit: 'HOURS')
            buildDiscarder(logRotator(numToKeepStr: '10', daysToKeepStr: '14'))
        }
        
        environment {
        	workingDir = "/tmp"
        	globalConfigDir="${workingDir}/globalConf"
        	projectDir="${workingDir}/project"

    		gitOrga = "NewCorpB2Bsmart-om"
    		gitToken = "MCCC55_GITHUB"
    		gitUrl = "github.tools.digital.engie.com"
    		gitProject = "${project}.git"
    		gitUser = "MCCC55"
    		gitEmail = "MCCC55@engie.com"
        }

        stages {
            
            stage('Checkout project') {
                steps {
                    echo "Checking out project source."
                    checkout scm: [$class: 'GitSCM', branches: [[name: "refs/tags/${tag}"]], extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${projectDir}"]], userRemoteConfigs: [[credentialsId: "${gitToken}", url: "https://${gitUrl}/${gitOrga}/${gitProject}"]]]
                    script{
                        //Remove SNAPSHOT in pom version
                        dir("${projectDir}") {
                            def pomFile = 'pom.xml'
                            def pom = readMavenPom file: pomFile
                            pom.version = readMavenPom().getVersion().replace("-SNAPSHOT", "")
                            writeMavenPom file: pomFile, model: pom
                        }
                    }
                }
            }
            
            stage('Publish') {
             	steps {
             		echo "Publish package to artifactory."
             		withMaven(
             		    maven: 'Maven 3.6.3',
                        mavenSettingsConfig: 'MuleMavenSettings',
                        options: [
                            openTasksPublisher(disabled: true), 
                            dependenciesFingerprintPublisher(disabled: true), 
                            artifactsPublisher(disabled: true), 
                            junitPublisher(disabled: true), 
                            jgivenPublisher(disabled: true), 
                            invokerPublisher(disabled: true), 
                            findbugsPublisher(disabled: true),
                            concordionPublisher(disabled: true), 
                            pipelineGraphPublisher(disabled: true)
                        ]
                    )
                    {
                 		dir("${projectDir}") {
    					    sh "mvn clean deploy"
    			        }
                    }
		  		}
			}
            
         	stage('Tag') {
             	steps {
             		echo "Tag project."
             		dir("${projectDir}") {
					    withCredentials([usernamePassword(credentialsId: "${gitToken}", passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                            script{
								GIT_USERNAME = URLEncoder.encode("${GIT_USERNAME}")
							}
                            sh "git config --global user.email '${gitEmail}'"
                            sh "git config --global user.name '${gitUser}'"
                            sh "git commit pom.xml -m 'no deployment. Remove SNAPSHOT in pom version ${readMavenPom().getVersion()}'"
                            sh "git tag -a ${readMavenPom().getVersion()} -m 'Jenkins tag ${readMavenPom().getVersion()}'"
                            sh "git push https://${GIT_USERNAME}:${GIT_PASSWORD}@${gitUrl}/${gitOrga}/${gitProject} --tags"
                        }
             		}
		  		}
			}
			
			stage('Commit') {
				// Increase pom version only for master branch
				when {
                    expression {return "${branch}".contains('master').toBoolean()}
                }
             	steps {
             		echo "Commit project."
             		dir("${projectDir}") {
             		    //Increase pom version
             		    script {
                            def pomFile = 'pom.xml'
                            def pom = readMavenPom file: pomFile
                            def version = pom.version.toString().split("\\.")
                            version[-2] = version[-2].toInteger()+1
                            version[-1] = 0
                            pom.version = version.join('.') + "-SNAPSHOT"
                            writeMavenPom file: pomFile, model: pom
                        }
					    withCredentials([usernamePassword(credentialsId: "${gitToken}", passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                            script{
								GIT_USERNAME = URLEncoder.encode("${GIT_USERNAME}")
							}
                            sh "git config --global user.email '${gitEmail}'"
                            sh "git config --global user.name '${gitUser}'"
                            sh "git commit pom.xml -m 'no deployment. New pom version ${readMavenPom().getVersion()}'"
                            sh "git push https://${GIT_USERNAME}:${GIT_PASSWORD}@${gitUrl}/${gitOrga}/${gitProject} HEAD:master"
                        }
             		}
		  		}
			}
        }
    }
}
