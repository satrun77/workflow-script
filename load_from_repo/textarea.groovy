/**
 * Place the content of this file into textarea of the plugin
 * Job parameterized with string parameter named "BRANCH_TO_BUILD"
 *
 */

import groovy.json.JsonSlurper

def flow

// Directory to store information about the job
def dataDir = 'jenkinsdata'

// Directory to clone the source code into
def srcDir = 'src'

// Jenkins admin username/password - used to make API request to update build description
// TOOD change this
def jenkinsUser = 'jenkins'
def jenkinsPass = '123'

// Source control
def scmUrl = 'git@repourl:project/name.git'
def scmcredentialsId = '8e7af354-c1fb-4298-a455-0d672a96b863'
def scmBrowser = [$class: 'GitLab', repoUrl: 'http://repourl/project/name/', version: '7.4']

// Build parameter must not be empty
if (BRANCH_TO_BUILD == '') {
    throw new hudson.AbortException("[Error] build started without specifying a branch name")
}

// Checkout branch
node {
    // Add description to build
    sh 'curl -s -u ' + jenkinsUser + ':' + jenkinsPass + ' --data "description=Branch: ' + BRANCH_TO_BUILD + '" --data "Submit=Submit" "' + env.JENKINS_URL + '/job/' + env.JOB_NAME + '/' + env.BUILD_NUMBER + '/submitDescription"'

    dir (BRANCH_TO_BUILD) {
        // Dir to store info about the job
        sh 'mkdir -p ' + dataDir

        // Checkout source code
        dir (srcDir) {
            // Check source code
            checkout changelog: true, poll: true, scm: [$class: 'GitSCM', branches: [[name: 'origin/' + BRANCH_TO_BUILD]], browser: scmBrowser, doGenerateSubmoduleConfigurations: false,  userRemoteConfigs: [[credentialsId: scmCredentialsId, url: scmUrl]]]

            // Load
            flow = load 'jenkins.groovy'

            // Make soure we are in correct branch
            flow.updateSourceCode(BRANCH_TO_BUILD)

            // Configs
            def str = readFile 'jenkins.json'
            flow.jenkinsConfig = new JsonSlurper().parseText(str)
            flow.dataDir = dataDir
            flow.srcDir = srcDir
            flow.branchName = BRANCH_TO_BUILD
            flow.branchOwner = jenkinsConfig['owner']
            flow.testers = jenkinsConfig['testers']
            flow.developers = jenkinsConfig['developers']
            flow.deployers = jenkinsConfig['deployers']
        }
    }
}
flow.executeBuild()
