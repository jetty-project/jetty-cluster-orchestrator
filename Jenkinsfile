#!groovy

pipeline {
  agent any
  stages {
    stage("Parallel Stage") {
      parallel {
        stage("Build / Test - JDK8") {
          agent { node { label 'linux' } }
          options { timeout(time: 30, unit: 'MINUTES') }
          steps {
            mavenBuild("jdk8", "clean install")
            script {
              if (env.BRANCH_NAME == 'main') {
                mavenBuild("jdk8", "deploy")
              }
            }
          }
        }
      }
    }
  }
}

/**
 * To other developers, if you are using this method above, please use the following syntax.
 *
 * mavenBuild("<jdk>", "<profiles> <goals> <plugins> <properties>"
 *
 * @param jdk the jdk tool name (in jenkins) to use for this build
 * @param cmdline the command line in "<profiles> <goals> <properties>"`format.
 * @return the Jenkinsfile step representing a maven build
 */
def mavenBuild(jdk, cmdline) {
  def mvnName = 'maven3'
  script {
    try {
      withEnv(["JAVA_HOME=${ tool "$jdk" }",
               "PATH+MAVEN=${ tool "$jdk" }/bin:${tool "$mvnName"}/bin",
               "MAVEN_OPTS=-Xms2g -Xmx4g -Djava.awt.headless=true"]) {
        configFileProvider([configFile(fileId: 'oss-settings.xml', variable: 'GLOBAL_MVN_SETTINGS')]) {
          sh "mvn --no-transfer-progress -s $GLOBAL_MVN_SETTINGS -B -V -e $cmdline"
        }
      }
    }
    finally
    {
      junit testResults: '**/target/surefire-reports/*.xml,**/target/invoker-reports/TEST*.xml', allowEmptyResults: true
    }
  }
}

// vim: et:ts=2:sw=2:ft=groovy
