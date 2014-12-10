/**
 * Place this file in repository
 *
 * @author Mohamed Alsharaf
 */

// Jenkins configuration from repo
jenkinsConfig = {}

// Master user
masterUser = ['jenkins': 'jenkins@email.com']

// Directory to store information about the job
dataDir = 'jenkinsdata'

// Directory to clone the source code into
srcDir = 'src'

// Current branch name && name of change owner
branchName = BRANCH_TO_BUILD
branchOwner = ''
branchSavedOwner = ''

// Users that can interact with different stages in the workflow
testers = {}
developers = {}
deployers = {}

//+---------------- End of configurations

def executeBuild() {
    // Build in subdirectory match the branch name
    node {
        dir (branchName) {
            dir (srcDir) {
                // Start Buid stage
                buildStage()
            }
        }
    }
}

def buildStage() {
    stage 'Build'
    stageAction = true
    while (stageAction) {
        def buildAction = askQuestion('build')
        processStageActions('build', buildAction)
        if (buildAction['Move to'] == 'Move to test stage') {
            stageAction = false
            // Start Test stage
            testStage();
            break;
        }
    }
}

def testStage() {
    stage 'Test (UAT)'
    stageAction = true
    while (stageAction) {
        def buildAction = askQuestion('test')
        processStageActions('test', buildAction)
        if (buildAction['Move to'] == 'Move to deploy stage') {
            stageAction = false
            deployStage();
            break
        }

        // Move back
        if (buildAction['Move to'] == 'Return to build stage') {
            stageAction = false
            buildStage();
            break;
        }
    }
}

def deployStage() {
    stage 'Deploy'
    stageAction = true
    while (stageAction) {
        def buildAction = askQuestion('deploy')
        processStageActions('deploy', buildAction)
        if (buildAction['Deploy build'] && branchOwner == branchSavedOwner) {
            stageAction = false
            break
        }

        // Move back
        if (buildAction['Move to'] == 'Return to build stage') {
            stageAction = false
            buildStage();
            break
        }
        // Move back
        if (buildAction['Move to'] == 'Return to test stage') {
            stageAction = false
            testStage();
            break
        }
    }
}

def askQuestion(stageType) {
    // By default only jenkins user can execute anything
    def submitter = masterUser.iterator().next().getKey()

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
        params.add([$class: 'hudson.model.StringParameterDefinition', defaultValue: branchSavedOwner, description: 'The workflow can\'t complete without the owner sign off', name: 'Branch Owner'])
    }

    def buildAction = input id: '7fd85613e7e068ad4f3bec8e717f2bc8', message: 'What would you like to do in ' + stageType + ' stage?', ok: 'Proceed', parameters: params, submitter: submitter
    return buildAction
}

def processStageActions(stageType, actions) {
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
            //            mail('test', 'Successfull build stage --> test stage')
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

    // Sign off
    branchSavedOwner = actions['Branch Owner']

    // Deploy build
    if (actions['Deploy build']) {
        if (branchOwner == branchSavedOwner) {
            startDeployingBuild(branchName, stageType)
        } else {
            echo '[Error] workflow must be signed off first before it can be deployed.'
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

return this;
