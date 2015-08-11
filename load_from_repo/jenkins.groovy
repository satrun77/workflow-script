/**
 * Place this file in repository
 *
 * @author Mohamed Alsharaf
 */

// Jenkins configuration from repo
jenkinsConfig = {}

// Directory to store information about the job
dataDir = 'jenkinsdata'

// Directory to clone the source code into
srcDir = 'src'

// Current branch name && name of change owner
branchName = ''
branchOwner = ''

// Users that can interact with different stages in the workflow
testers = {}
developers = {}
deployers = {}

workspace = pwd() + '/..'

//+---------------- End of configurations

def executeBuild() {
    // Load
    echo '[Info] Branch: ' + branchName
    sh 'git log --pretty=format:"[Info] Commit: %h%x09%an%x09%ad%x09%s\n" -1'
    echo '[Info] Logged in as: ' + hudson.model.User.current().id
    echo '[Error] Issue with Jenkins and Window. Displays SYSTEM as current logged in user......'

    // Start Buid stage
    buildStage()
}

def buildStage() {
    stage 'Build'

    setupEnv()

    // Init tests
    basicTesting()

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
    def submitter = ''
    /*
    // Issue with Jenkins and Window. Displays SYSTEM as current logged in user......
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
     */

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
        updateSourceCode();
    }

    // Tests
    if (actions['Unit test'] || actions['Behat test'] || actions['Syntax test']) {
        testingStatus = true

        parallel unitTests: {
            if (actions['Unit test']) {
                showUnitTests()
            }
        }, behatTests: {
            if (actions['Behat test']) {
                showBehatTests()
            }
        }, filesSynatx: {
            if (actions['Syntax test']) {
                startSyntaxTests()
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
    if (stageType == 'deploy') {
        if (actions['Deploy build'] && branchOwner == branchSavedOwner) {
            startDeployingBuild(branchName, stageType)
        } else {
            echo '[Error] workflow must be signed off first before it can be deployed.'
        }
    } else if (actions['Deploy build']) {
        startDeployingBuild(branchName, stageType)
    }
}

def showUnitTests() {
    echo '[Action] Unit testing...'

    def test = testsUserInput('unit_tests', 'Would like to execute specific unit tests or all?', 'Select tests to execute')
    startUnitTests(test)
}

def showBehatTests() {
    echo '[Action] Behat testing...'

    def test = testsUserInput('behat_tests', 'Would like to execute specific behat tests or all?', 'Select tests to execute')
    startBehatTests(test)
}

def startUnitTests(test) {
    echo '[Action] ... ' + test
    sh 'php admin/tool/phpunit/cli/init.php'
    sh "vendor/bin/phpunit $test"
}

def startBehatTests(test) {
    echo '[Action] ... ' + test

    if (test != '') {
        test = '--tags ' + test
    }

    sh """
php admin/tool/behat/cli/init.php
job="Stream-${branchName}"
behatconf="/var/www/sitedata/\$job/behat/behat/behat.yml"


php -S localhost:8000 -t . > /dev/null 2>&1 & echo \$!

if ! [[ `ps aux | grep "[s]elenium"` ]]
then
    currentdisplay=\$DISPLAY
    export DISPLAY=:10

    echo "Starting Xvfb ..."
    Xvfb :10 -ac > /dev/null 2>&1 & echo \$!

    echo "Starting Selenium ..."
    nohup java -jar /var/www/sitedata/selenium.jar > /dev/null 2>&1 & echo \$!

    export DISPLAY=\$currentdisplay

fi

vendor/bin/behat --config \$behatconf --format=pretty $test
"""

/*
ps aux | grep "[p]hp -S localhost:8000"

// handler multiple tests on same server
    sh """

php admin/tool/behat/cli/init.php

job="Stream-${branchName}"

behatconf="/var/www/sitedata/\$job/behat/behat/behat.yml"

if ! [[ `ps aux | grep \"[p]hp -S \$job.localhost:\"` ]]
then

    port=7999
    while true
    do
        port=\$((port+1))
        if ! [[ `ps aux | grep "[p]hp -S" | grep ".localhost:\$port"` ]]
        then
            break;
        fi
    done

    echo "Starting internal webserver at \$job.localhost:\$port...";
    php -S \$job.localhost:\$port -t . > /dev/null 2>&1 & echo \$!
else
    port=`ps aux | grep "[p]hp -S \$job.localhost:" | egrep -o \$job.localhost:[0-9]+ | awk -F ":" '{print \$2}'`
fi

numberregex='^[0-9]+\$'
if ! [[ \$port =~ \$numberregex ]] ; then
    echo "ERROR: bad port: \$port!"
    exit 1
fi


sed -i -e s/localhost:8000/localhost:\$port/g \$behatconf
sed -i -e s/localhost:8000/localhost:\$port/g ./config.php

if ! [[ `ps aux | grep "[s]elenium"` ]]
then
    currentdisplay=\$DISPLAY
    export DISPLAY=:10

    echo "Starting Xvfb ..."
    Xvfb :10 -ac > /dev/null 2>&1 & echo \$!

    echo "Starting Selenium ..."
    nohup java -jar /var/www/sitedata/selenium.jar > /dev/null 2>&1 & echo \$!

    export DISPLAY=\$currentdisplay

fi

echo "RUNNING BEHAT TESTS"
vendor/bin/behat --config \$behatconf --format=pretty $test

"""
*/
}

def testsUserInput(name, message, description) {
    def options = jenkinsConfig[name].trim() + '\n' + 'All tests'
    def test = input message: message, ok: 'Go', parameters: [[$class: 'ChoiceParameterDefinition', choices: options, description: description, name: name]], submitter: ''

    def selected = test.tokenize( ':' )
    def label = selected[0].trim()
    def value = selected[1]

    if (value == null) {
        value = ''
    } else {
        value = value.trim()
    }

    echo '[Action] testing: ' + label

    return value
}

def startSyntaxTests() {
    echo '[Action] Checking for syntax errrors...'

    sh """
if [ -e $workspace/$dataDir/linterrors ]; then
   rm $workspace/$dataDir/linterrors
fi

find $workspace/$srcDir -path $workspace/$srcDir/vendor -prune -o -type f -name '*.php' -print0 | while read -d \$'\0' file
   do
     php -l \"\$file\" 2>> $workspace/$dataDir/linterrors 
done

if [ -s $workspace/$dataDir/linterrors ]; then 
   echo "SYNTAX ERRORS FOUND:"
   cat $workspace/$dataDir/linterrors
   exit 1
fi
"""

}

def startDeployingBuild(branchName, stageType) {
    echo '[Action] deploying the source code to ' + stageType + ' environment'
    echo '[Info] send confirmation email about a successful deployment'

    sh "php admin/cli/install_database.php --agree-license --adminuser=admin --adminpass=admin123 --shortname=${branchName} --fullname=${branchName}"

    echo "[Info] admin user: admin, password: admin123, url: http://azu-jenkinsn1/workspace/Stream-{branchName}/src"
}

def basicTesting() {
    // Only specifc tests can be used for the first initial tests
    parallel unitTests: {
        if (jenkinsConfig['default_unit_test'] != '') {
            startUnitTests(jenkinsConfig['default_unit_test'])
        }
    }, behatTests: {
        if (jenkinsConfig['default_behat_test'] != '') {
            //startBehatTests(jenkinsConfig['default_behat_test'])
        }
    }, filesSynatx: {
        // stop for now: startSyntaxTests()
    }
}

def updateSourceCode() {
    echo '[Action] Update source code'
    sh 'git fetch --all'
    sh 'git reset --hard origin/' + branchName
}

def setupEnv() {
    // Download and install composer 
    //sh "curl http://getcomposer.org/installer | php"
    //sh "php composer.phar install" 
    // Workaround for now ONLY - copy vendor components from cache files 
    sh "rsync -arltD --stats --human-readable /var/www/sitedata/vendor ./"

    // Create site data directory
    // TODO replace branchNmae to JOB Name
    if (branchName != '') {
        sh "rm -rf /var/www/sitedata/Stream-$branchName"
        sh "mkdir -p /var/www/sitedata/Stream-$branchName"
        sh "chown massey:apache /var/www/sitedata/Stream-$branchName"
        sh "chmod 777 /var/www/sitedata/Stream-$branchName"
    } else {
        error 'Branch name is missing'
    }

    // database
    // TODO replace branchNmae to JOB Name
    sh "dropdb --if-exists Stream-$branchName"
    sh "createdb -E utf8 -O postgres Stream-$branchName"

    // Setup config
    sh "cp /var/www/sitedata/config.php config.php"
}

def mail() {
    mail subject: "failed with #{e.message}", recipients: 'admin@somewhere'
}

return this;
