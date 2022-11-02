def call(AGENT_LABEL, PROJECT, RELEASE, ENVIRONMENT) {
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
			projectConfigDir="${workingDir}/projectConf"
			projectDir="${workingDir}/project"
			
			jarName="${project}-${release}-mule-application.jar"
			pomName="${project}-${release}.pom"
			
			artifactoryProjectDir="${projectDir}/com/engie/mulesoft/app/api/${project}/${release}/"
			
			artifactoryId = "artifactory-smart-om"
			artifactoryJarFilepath = "smart-om-core-maven-upload-dublin/com/engie/mulesoft/app/api/${project}/${release}/${jarName}"
			artifactoryPomFilepath = "smart-om-core-maven-upload-dublin/com/engie/mulesoft/app/api/${project}/${release}/${pomName}"
		
			gitOrga = "NewCorpB2Bsmart-om"
			gitToken = "MCCC55_GITHUB"
			gitUrl = "github.tools.digital.engie.com"
			gitConfGlobalProject = "${environment == "PROD" ? "conf-mule-global.git" : "conf-mule-global-nonprod.git"}"
			gitConfProject = "${project.replace("mule-", "conf-")}"
		}

		stages {
			
			stage('Checkout project') {
				steps {
					echo "Checkout project from artifactory."
					sh "mkdir -p ${projectDir}"
					echo "Download ${artifactoryJarFilepath}"
					rtDownload (
						serverId: "${artifactoryId}",
						spec: """{
							"files": [
								{
								"pattern": "${artifactoryJarFilepath}",
								"target": "${projectDir}/"
								}
							]
						}""",
						failNoOp: true )
					
					echo "Download ${artifactoryPomFilepath}"
					rtDownload (
						serverId: "${artifactoryId}",
						spec: """{
							"files": [
								{
								"pattern": "${artifactoryPomFilepath}",
								"target": "${projectDir}/"
								}
							]
						}""",
						failNoOp: true )
						
					dir("${artifactoryProjectDir}") {
						sh "cp ${pomName} pom.xml"
					}
				}
			}
		
			stage('Checkout config') {
				steps {
					echo "Checking out project config and deploy shared config."
					checkout scm: [$class: 'GitSCM', branches: [[name: 'master']], extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${globalConfigDir}"]], userRemoteConfigs: [[credentialsId: "${gitToken}", url: "https://${gitUrl}/${gitOrga}/${gitConfGlobalProject}"]]]
					checkout scm: [$class: 'GitSCM', branches: [[name: 'master']], extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${projectConfigDir}"]], userRemoteConfigs: [[credentialsId: "${gitToken}", url: "https://${gitUrl}/${gitOrga}/${gitConfProject}"]]]
					// Read all mule app and deploy shared properties into local variables
					script {
						echo "Reading shared config from ${gitConfGlobalProject}..."
						props = readProperties(file: "${globalConfigDir}/env/deploy-properties/${environment}/deploy.properties" )
						props.each { key, value ->
							echo "Property $key = $value"
						}
						deployAnypointUser=props['deployAnypointUser']
						deployConfigEnv=props['deployConfigEnv']
						deployDeployEnv=props['deployDeployEnv']
						deployLogServerHost=props['deployLogServerHost']
						deployLogServerPort=props['deployLogServerPort']
						deployLogServerChannel=props['deployLogServerChannel']
						
						echo "Reading project config from ${gitConfProject}..."
						props = readProperties(file: "${projectConfigDir}/${environment}/deploy.properties" )
						props.each { key, value ->
							echo "Property $key = $value"
						}
						deployChWorkerType=props['deploy.ch.workerType']
						deployChWorkerNumber=props['deploy.ch.workerNumber']
					}
				}
			}
			
			stage('Configure') {
				steps {
					echo "Configuring application."
					dir("${artifactoryProjectDir}") {
						sh "jar uf *.jar -C ${globalConfigDir}/global/log4j2/ch log4j2.xml"
						sh "mkdir ${globalConfigDir}/env/properties"
						sh "cp -R ${globalConfigDir}/env/project-properties/* ${globalConfigDir}/env/properties"
						sh "jar uf *.jar -C ${globalConfigDir}/env properties/${environment}"
						sh "mkdir -p ${projectConfigDir}/properties/${environment}"
						sh "cp -R ${projectConfigDir}/${environment}/*.yaml ${projectConfigDir}/properties/${environment}"
						sh "jar uf *.jar -C ${projectConfigDir} properties/${environment}"
					}
				}
			}
			
			stage('Deploy') {
				steps {
				   withCredentials(
					   [
						   string(credentialsId: "mule-config-splunk-logServerAuditToken-${environment}", variable: 'deployLogServerAuditToken'),
						   string(credentialsId: "mule-config-splunk-logServerPayloadAuditToken-${environment}", variable: 'deployLogServerPayloadToken'),
						   string(credentialsId: "mule-config-splunk-logServerLogToken-${environment}", variable: 'deployLogServerLogToken'),
						   string(credentialsId: "mule-config-secretkey-${environment}", variable: 'deployConfigEncryptionkey')
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
							echo "Deploying project ${project}-${release} to CloudHub {environment} with maven serverId profile = ${deployAnypointUser}"
							dir("${artifactoryProjectDir}") {
								sh "mvn -P${environment} mule:deploy -Ddeploy.anypointUser=${deployAnypointUser} -Ddeploy.config.encryptionkey=${deployConfigEncryptionkey} -Ddeploy.configenv=${deployConfigEnv} -Ddeploy.deployenv=${deployDeployEnv} -Ddeploy.logServerHost=${deployLogServerHost} -Ddeploy.logServerPort=${deployLogServerPort} -Ddeploy.logServerAuditToken=${deployLogServerAuditToken} -Ddeploy.logServerPayloadToken=${deployLogServerPayloadToken} -Ddeploy.logServerLogToken=${deployLogServerLogToken} -Ddeploy.logServerChannel=${deployLogServerChannel} -Ddeploy.ch.workerType=${deployChWorkerType} -Ddeploy.ch.workerSize=${deployChWorkerNumber} -Dmule.artifact=${jarName}"
							}
						}
					}
				}
			}
		}
	}
}
