// pipeline/myPipeline.groovy
// 

import groovy.json.JsonOutput

def call(body = {}) {
    echo "start('Run')"

    def config = [
            name                           : null,
            buildVersion                   : null,
            files                          : 'microservice.yml,*.microservice,library.yml',
            numToKeep                      : isDefaultBranch() ? 15 : 3,
            disableConcurrentBuilds        : !isDefaultBranch(),
            stopConcurrentBuilds           : false,
            notifySparkRoomId              : null,
            sendMentionsInRoomNotifications: false,
            services                       : [],
            mergeTarget                    : 'master',
            postCheckoutMerge              : false,
            builder                        : 'BUILDER',
            preCheckout                    : null,
            preBuild                       : null
            build                          : { services ->
                sh "mvn -U --batch-mode versions:set -DnewVersion=$env.BUILD_VERSION && mvn verify"
            },
            postBuild                      : null,
            postNodeRelease                : null,
            autoArchive                    : !isPullRequest(),
            postArchiveArtifacts           : null,
            deployTo                       : ['integration'],
            integration                    : [
                    deployMode                : 'input',
                    canarySteps               : [[traffic: 10]],
                    canaryRemoval             : 'prompt',
                    submitter                 : 'jenkins',
                    deployedBuildIds          : [:],
                    postDeployTests           : ['default'],
                    postDeployTestLayers      : ['integration'],
                    postDeployRetryTests      : true,
                    postDeploySteps           : [:],
                    runConsumerTests          : true,
                    runSecurityScans          : false,
                    pushGitBranch             : true,
                    harnessPipeline           : null,
            ],
            autoMergeBehavior              : 'merge',
            harnessCheck                   : true,
            harnessDeployed                : false,
    ]

    try {
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body.delegate = config
        body()

        def postCheckout = {
            // Find the first microservice file and get the pipeline name or id from it.
            if (!config.name) {
                def data = readYaml(file: findFiles(glob: config.files)[0].path)
                config.name = data.pipeline ?: data.id
            }

            pipelineProperties(
                    name: config.name,
                    numToKeep: config.numToKeep,
                    notifySparkRoomId: config.notifySparkRoomId,
                    sendMentionsInRoomNotifications: config.sendMentionsInRoomNotifications,
                    disableConcurrentBuilds: config.disableConcurrentBuilds,
                    stopConcurrentBuilds: config.stopConcurrentBuilds
            )

            // Get some Harness info
            if (config.harnessCheck) {
                def pipelineDir = '.pipeline.harness'
                checkoutPipeline(target: pipelineDir, credentialsId: 'deploy-key-WebexSquared-pipeline')
                def data = readYaml file: "${pipelineDir}/pipelines.yml"
                if (data) {
                    def service_list = data.get('harness_deploys', [])
                    if (service_list.contains(config.name)) {
                        config.harnessDeployed = true
                    }
                }
                sh "rm -rf ${pipelineDir}"
            }
            echo("HARNESS: Pipeline's Migration Status: ${config.harnessDeploy ? 'DONE': 'NOT YET DONE'}")
        }

        buildStage(null, label: config.builder,
                buildVersion: config.buildVersion,
                preCheckout: config.preCheckout,
                postCheckout: postCheckout,
                services: config.services,
                junitPattern: config.junitPattern,
                spotbugsOpts: config.spotbugsOpts) { serviceMap ->

            if (config.postCheckoutMerge || isAutoMergeBranch()) {
                sh 'git config user.email "<>" && git config user.name "Jenkins"'
                sh "git merge origin/$config.mergeTarget"
            }

            config.preBuild?.call(serviceMap)
            config['build'](serviceMap)
            config.postBuild?.call(serviceMap)
        }

        config.postNodeRelease?.call()

        for (int i = 0; i < config.deployTo.size(); i++) {
            deployTo(config.deployTo[i], config[config.deployTo[i]], config.harnessDeployed)
        }
    } finally {
    }
}

def deployTo(cloud, config, harnessDeployed=false) {
    def stageName = "Deploy to $cloud"
    def buildIds = [:]
    def modCanarySteps = harnessDeployed ? null : config.canarySteps
    def allowCanarySteps = harnessDeployed || config.canarySteps
    def body = config.deploy ?: { response ->
        buildIds = deploy(cloud, archiveOnly: response.choice == 'archive',
                steps: response.choice == 'canary' ? modCanarySteps : null,
                autoRemove: config.canaryRemoval, pushGitBranch: config.pushGitBranch,
                submitter: config.submitter, harnessChecked: true, harnessOverride: config.harnessPipeline,
                harnessCanary: harnessDeployed && response.choice == 'canary', harnessDeploy: harnessDeployed)
        config.deployedBuildIds = buildIds
    }

    def result = approveStage(stageName, body,
            autoResponse: currentBuild.currentResult == 'SUCCESS' && config.deployMode != 'input' ? config.deployMode : null,
            useChoices: true,
            canaryChoice: allowCanarySteps,
            submitter: config.submitter,
            changeLogSince: "deployed/$cloud",
            permitFedrampPrompt: cloud == 'production')

    if (!result.skipped) {
        postDeploySteps = [:]
        if (config.postDeployTests) {
            postDeploySteps["$cloud tests"] = {
                runTests(cloud, buildIds.archiveBuildId, idIncludes: config.postDeployTests,
                        retryBuild: config.postDeployRetryTests, specificEnvironments: config.postDeployTestLayers)
            }
        }

        if (config.runConsumerTests) {
            postDeploySteps['consumer tests'] = {
                runConsumerTests(includes: config.consumerTestsIncludes, excludes: config.consumerTestsExcludes,
                        target_cloud: cloud, tags: config.consumerTestsTags,
                        promptForAction: config.consumerTestsPromptAction)
            }
        }
        if (config.runSecurityScans) {
            postDeploySteps['security scans'] = {
                runSecurityScanJob(buildIds.archiveBuildId)
            }
        }
        if (config.postDeploySteps) {
            postDeploySteps.putAll(config.postDeploySteps)
        }

        if (postDeploySteps) {
            stage("$cloud post-deploy") {
                checkpoint "$cloud post-deploy"
                setBuildName()
                parallel(postDeploySteps)
            }
        }

        if (config.publish) {
            publish(upstreamJob: buildIds.archiveBuildId)
        }
    } else {
        stage("$cloud post-deploy") {
            echo "Skipping stage since $cloud deploy was skipped."
        }
    }
}

def openPullRequest(payload = [:]) {
    writeFile(file: 'payload.json', text: JsonOutput.toJson(payload))
    def curlOpts = '--fail -q -s -o /dev/null -w "%{http_code}\n"'
    def headers = "-H 'Authorization: token "+GIT_PASSWORD+"' -H 'Content-Type: application/json'"
    sh "curl $curlOpts $headers -d '@payload.json' -X POST ${env.GITHUB_API_URL}pulls"
}

def deleteBranch(branch) {
    def curlOpts = '--fail -q -s -o /dev/null -w "%{http_code}\n"'
    def headers = "-H 'Authorization: token "+GIT_PASSWORD+"'"
    sh "curl $curlOpts $headers -d -X DELETE ${env.GITHUB_API_URL}/refs/heads/$branch"
}
