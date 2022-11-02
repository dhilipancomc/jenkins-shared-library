def call(AGENT_LABEL) {
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
        	
            pomArtifactId = readMavenPom().getArtifactId()
    		pomVersion = readMavenPom().getVersion()
    		
    		gitOrga = "NewCorpB2Bsmart-om"
    		gitToken = "MCCC55_GITHUB"
    		gitUrl = "github.tools.digital.engie.com"
    		
    		gitCurrentUrlindexOfSlash="${GIT_URL.indexOf('/')}"
    		gitCurrentUrlStart="${GIT_URL.substring(0,gitCurrentUrlindexOfSlash.toInteger()+2)}"
    		gitCurrentUrlEnd="${GIT_URL.substring(gitCurrentUrlindexOfSlash.toInteger()+2)}"
    		
    		GIT_COMMIT_MSG = sh (script: 'git log -1 --pretty=%B ${GIT_COMMIT}', returnStdout: true).trim()
            noDeployment = "no deployment"
        }

        stages {
            
            stage('Checkout config') {
				when {
                    expression {return "${!GIT_COMMIT_MSG.contains(noDeployment)}".toBoolean()}
                }
                steps {
                    echo "Checking out project project and deploy shared config."
                    checkout scm: [$class: 'GitSCM', branches: [[name: 'master']], extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${globalConfigDir}"]], userRemoteConfigs: [[credentialsId: "${gitToken}", url: "https://${gitUrl}/${gitOrga}/conf-mule-global-nonprod.git"]]]
                    // Read all mule app and deploy shared properties into local variables
                    script {
                        echo "Reading shared config from conf-mule-global-nonprod.git..."
                        props = readProperties(file: "${globalConfigDir}/env/deploy-properties/DEV/deploy.properties" )
                        props.each { key, value ->
                            echo "Property $key = $value"
                        }
                        deployAnypointUser=props['deployAnypointUser']
                        deployConfigEnv=props['deployConfigEnv']
                        deployDeployEnv=props['deployDeployEnv']
                        deployLogServerHost=props['deployLogServerHost']
                        deployLogServerPort=props['deployLogServerPort']
                        deployLogServerChannel=props['deployLogServerChannel']
                    }
                }
            }

            stage('Configure') {
				when {
                    expression {return "${!GIT_COMMIT_MSG.contains(noDeployment)}".toBoolean()}
                }
                steps {
                    echo "Configuring application."
                    // The build requires configuration on the source. We use a copy of the checked out project.
                    sh "mkdir -p ${projectDir}"
                    sh "mkdir -p ${globalConfigDir}"
                    sh "cp -R . ${projectDir}"
                    sh "cp -f ${globalConfigDir}/global/log4j2/ch/log4j2.xml ${projectDir}/src/main/resources"
                }
            }
            
            stage('Build') {
				when {
                    expression {return "${!GIT_COMMIT_MSG.contains(noDeployment)}".toBoolean()}
                }
                steps {
                    withMaven(
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
                        echo "Building project source..."
                        //sh "more /home/jenkins/agent/workspace/mulesoft-dev@tmp/*/settings.xml"
                        
                        dir("${projectDir}") {
					    	sh "mvn clean package install"
					    }
                    }
                }
            }

            stage('Deploy') {
				when {
                    expression {return "${!GIT_COMMIT_MSG.contains(noDeployment)}".toBoolean()}
                }
                steps {
                   withCredentials(
                       [
                           string(credentialsId: 'mule-config-splunk-logServerAuditToken-DEV', variable: 'deployLogServerAuditToken'),
                           string(credentialsId: 'mule-config-splunk-logServerPayloadAuditToken-DEV', variable: 'deployLogServerPayloadToken'),
                           string(credentialsId: 'mule-config-splunk-logServerLogToken-DEV', variable: 'deployLogServerLogToken'),
                           string(credentialsId: 'mule-config-secretkey-DEV', variable: 'deployConfigEncryptionkey')
                    ]) 
                   { 
                        withMaven(
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
                            echo "Deploying project ${pomArtifactId} to CloudHub with maven serverId profile = ${deployAnypointUser}"
							dir("${projectDir}") {
					    		sh "mvn -DmuleDeploy deploy -Ddeploy.anypointUser=${deployAnypointUser} -Ddeploy.config.encryptionkey=${deployConfigEncryptionkey} -Ddeploy.configenv=${deployConfigEnv} -Ddeploy.deployenv=${deployDeployEnv} -Ddeploy.logServerHost=${deployLogServerHost} -Ddeploy.logServerPort=${deployLogServerPort} -Ddeploy.logServerAuditToken=${deployLogServerAuditToken}  -Ddeploy.logServerPayloadToken=${deployLogServerPayloadToken} -Ddeploy.logServerLogToken=${deployLogServerLogToken} -Ddeploy.logServerChannel=${deployLogServerChannel}"
					    	}
                        }
                    }
                }
            }
            
         	stage('Tag') {
           		// The succesfully deployed SNAPSHOT must now be tagged ${pomArtifactId}_${pomVersion}
           		// This will be used for a candidate release pipeline
           		when {
                    expression {return "${!GIT_COMMIT_MSG.contains(noDeployment)}".toBoolean()}
                }
             	steps {
             		echo "Tag project."
					withCredentials([usernamePassword(credentialsId: "${gitToken}", passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
						script{
                            GIT_USERNAME = URLEncoder.encode("${GIT_USERNAME}")
                        }
                        sh "git tag -a ${pomVersion} -m 'Jenkins'"
                        sh "git push ${gitCurrentUrlStart}${GIT_USERNAME}:${GIT_PASSWORD}@${gitCurrentUrlEnd} -f --tags"
                    }
		  		}
			}
        }
    }
}
