/**
 * Example workflow script for https://github.com/jenkinsci/workflow-plugin
 *
 * @author Mohamed Alsharaf
 */

// Directory to store information about the job
def datadir = 'jenkinsdata'

// Directory to clone the source code into
def srcdir = 'src'

// Jenkins admin username/password - used to make API request to update build description
def jenkinsuser = 'jenkins'
def jenkinspass = 'password'

// Users that can interact with different stages in the workflow
master = ['jenkins': 'jenkins@email.com']
testers = [
    'test1': 'test1@email.com',
    'test2': 'test2@email.com'
]
developers = [
    'dev1': 'dev1@email.com'
]
deployers = [
    'deploy1': 'deploy1@email.com'
]

// Source control
def scmUrl = 'git@repourl:project/name.git'
def scmcredentialsId = '8e7af354-c1fb-4298-a455-0d672a96b863'
def scmBrowser = [$class: 'GitLab', repoUrl: 'http://repourl/project/name/', version: '7.4']

//+---------------- End of configurations

// Setup enviroment
def setupInput = input id: 'setupInput', message: 'Initial build setup:', ok: 'Proceed', parameters: [[$class: 'hudson.model.StringParameterDefinition', defaultValue: '', description: 'Enter a valid branch name', name: 'Branch'],[$class: 'hudson.model.StringParameterDefinition', defaultValue: '', description: 'Set name of the person that can sign off this workflow', name: 'Sign Off']]
def branchName = setupInput['Branch']
signOff = setupInput['Sign Off']
if (branchName == '') {
    throw new hudson.AbortException("[Error] build started without specifying a branch name")
}

// Checkout branch
node {
    // Add description to build
    sh 'curl -s -u ' + jenkinsuser + ':' + jenkinspass + ' --data "description=Branch: ' + branchName + '" --data "Submit=Submit" "' + env.JENKINS_URL + '/job/' + env.JOB_NAME + '/' + env.BUILD_NUMBER + '/submitDescription"'

    dir (branchName) {
        // Dir to store info about the job
        sh 'mkdir -p ' + datadir

        // Checkout source code
        dir (srcdir) {
            checkout changelog: true, poll: true, scm: [$class: 'GitSCM', branches: [[name: '*/'+branchName]], browser: scmBrowser, doGenerateSubmoduleConfigurations: false,  userRemoteConfigs: [[credentialsId: scmcredentialsId, url: scmUrl]]]
            // Get branch name
            updateSourceCode(branchName)
        }
    }
}

// Build in subdirectory match the branch name
node {
    dir (branchName) {
        dir (srcdir) {
            // Start Buid stage
            buildStage(branchName)
        }
    }
}

def askQuestion(stageType) {
    // By default only jenkins user can execute anything
    def submitter = master.iterator().next().getKey()

    // Change submitter
    if (stageType == 'build') {
        if (developers.containsKey(hudson.model.User.current().id)) {
            submitter = hudson.model.User.current().id
        }
    } else if (stageType == 'test') {
        if (testers.containsKey(hudson.model.User.current().id)) {
            submitter = hudson.model.User.current().id
        }
    } else if (stageType == 'deploy') {
        if (deployers.containsKey(hudson.model.User.current().id)) {
            submitter = hudson.model.User.current().id
        }
    }

    def params = [
        [$class: 'hudson.model.BooleanParameterDefinition', defaultValue: false, description: 'PHP Unit tests', name: 'Unit test'],
        [$class: 'hudson.model.BooleanParameterDefinition', defaultValue: false, description: 'Behat tests', name: 'Behat test'],
        [$class: 'hudson.model.BooleanParameterDefinition', defaultValue: false, description: 'Files syntax check', name: 'Syntax test'],
        [$class: 'hudson.model.BooleanParameterDefinition', defaultValue: false, description: 'Update source code', name: 'Update code'],
    ]
    def moveOptions = 'Stay in current stage\n'

    if (stageType == 'build') {
        moveOptions += 'Move to test stage\n'
    }
    if (stageType == 'test') {
        moveOptions += 'Return to build stage\n'
        moveOptions += 'Move to deploy stage\n'
    }
    if (stageType == 'deploy') {
        moveOptions += 'Return to build stage\n'
        moveOptions += 'Return to test stage\n'
    }

    params.add([$class: 'hudson.model.ChoiceParameterDefinition', choices: moveOptions, description: 'Move to another stage', name: 'Move to'])
    params.add([$class: 'hudson.model.BooleanParameterDefinition', defaultValue: false, description: 'Would you like to deploy code after successful test to ' + stageType + ' environment?', name: 'Deploy build'])

    if (stageType == 'deploy') {
        params.add([$class: 'hudson.model.StringParameterDefinition', defaultValue: '', description: 'The workflow can\'t complete without the owner sign off', name: 'Sign Off'])
    }

    def buildAction = input id: '7fd85613e7e068ad4f3bec8e717f2bc8', message: 'What would you like to do in ' + stageType + ' stage?', ok: 'Proceed', parameters: params, submitter: submitter
    return buildAction
}

def buildStage(branchName) {
    stage 'Build'
    stageAction = true
    while (stageAction) {
        def buildAction = askQuestion('build')
        processStageActions('build', branchName, buildAction)
        if (buildAction['Move to'] == 'Move to test stage') {
            stageAction = false
            // Start Test stage
            testStage(branchName);
            break;
        }
    }
}

def testStage(branchName) {
    stage 'Test (UAT)'
    stageAction = true
    while (stageAction) {
        def buildAction = askQuestion('test')
        processStageActions('test', branchName, buildAction)
        if (buildAction['Move to'] == 'Move to deploy stage') {
            stageAction = false
            deployStage(branchName);
            break
        }

        // Move back
        if (buildAction['Move to'] == 'Return to build stage') {
            stageAction = false
            buildStage(branchName);
            break;
        }
    }
}

def deployStage(branchName) {
    stage 'Deploy'
    stageAction = true
    while (stageAction) {
        def buildAction = askQuestion('deploy')
        processStageActions('deploy', branchName, buildAction)
        if (buildAction['Deploy build'] && signOff == buildAction['Sign Off']) {
            stageAction = false
            break
        }

        // Move back
        if (buildAction['Move to'] == 'Return to build stage') {
            stageAction = false
            buildStage(branchName);
            break
        }
        // Move back
        if (buildAction['Move to'] == 'Return to test stage') {
            stageAction = false
            testStage(branchName);
            break
        }
    }
}

def processStageActions(stageType, branchName, actions) {
    testingStatus = false

    // Update source code
    if (actions['Update code']) {
        updateSourceCode(branchName);
    }

    // Tests
    if (actions['Unit test'] || actions['Behat test'] || actions['Syntax test']) {
        testingStatus = true

        parallel unitTests: {
            if (actions['Unit test']) {
                startUnitTests(branchName)
            }
        }, filesSynatx: {
            if (actions['Behat test']) {
                startBehatTests(branchName)
            }
        }, behatTests: {
            if (actions['Syntax test']) {
                startSyntaxTests(branchName)
            }
        }
    }

    // Change stage - Move to
    if (actions['Move to'] != 'Stay in current stage') {
        if (actions['Move to'] == 'Move to test stage') {
            echo '[Info] Send email to testing team. Successfull build stage --> test stage.'
        }

        if (actions['Move to'] == 'Move to deploy stage') {
            echo '[Info] Send email to deploy team. Successfull test stage --> deply stage.'
        }

        if (actions['Move to'] == 'Return to build stage') {
            echo '[Info] Send email to build team. ' + stageType + ' stage rejected the build.'
        }
        if (actions['Move to'] == 'Return to test stage') {
            echo '[Info] Send email to build team. ' + stageType + ' stage rejected the build.'
        }
    }

    // Deploy build
    if (actions['Deploy build']) {
        if (signOff == actions['Sign Off']) {
            startDeployingBuild(branchName, stageType)
        } else {
            echo '[Error] workflow must be sign off first before it can be deployed.'
        }
    }
}

def startUnitTests(branchName) {
    echo '[Action] Unit testing...'
}

def startBehatTests(branchName) {
    echo '[Action] Behat testing...'
}

def startSyntaxTests(branchName) {
    echo '[Action] Checking for syntax errrors...'
}

def startDeployingBuild(branchName, stageType) {
    echo '[Action] deploying the source code to ' + stageType + ' environment'
    echo '[Info] send confirmation email about a successful deployment'
}

def updateSourceCode(branchName) {
    echo '[Action] Update source code'
    sh 'git fetch --all'
    sh 'git reset --hard origin/' + branchName
}

def mail() {
    mail subject: "failed with #{e.message}", recipients: 'admin@somewhere'
}
