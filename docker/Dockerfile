# Local Jenkins for manual JenkSpotter testing. Not for production use.
FROM jenkins/jenkins:lts-jdk21

USER root
# A small git repository with two branches feeds the multibranch pipeline.
RUN set -eux; \
    git config --global user.email dev@example.invalid; git config --global user.name dev; \
    git init -b main /tmp/app; cd /tmp/app; \
    printf '%s\n' \
      'pipeline {' \
      '  agent any' \
      '  stages {' \
      '    stage("Build") { steps { echo "Building ${env.BRANCH_NAME}"; sleep 5 } }' \
      '    stage("Test") { steps { writeFile file: "r.xml", text: "<testsuite name=\\"S\\" tests=\\"2\\" failures=\\"1\\"><testcase classname=\\"com.example.AppTest\\" name=\\"works\\"/><testcase classname=\\"com.example.AppTest\\" name=\\"breaks\\"><failure message=\\"expected 1 but was 2\\">trace</failure></testcase></testsuite>"; junit testResults: "r.xml", allowEmptyResults: true } }' \
      '    stage("Package") { steps { writeFile file: "app.txt", text: "artifact"; archiveArtifacts "app.txt" } }' \
      '  }' \
      '}' > Jenkinsfile; \
    git add Jenkinsfile; git commit -m init; \
    git checkout -b feature/login; echo "// feature" >> Jenkinsfile; git commit -am feature; \
    mkdir -p /opt/repos; git clone --bare /tmp/app /opt/repos/app.git; \
    chown -R jenkins:jenkins /opt/repos; rm -rf /tmp/app
USER jenkins

RUN jenkins-plugin-cli --plugins \
    configuration-as-code job-dsl workflow-aggregator cloudbees-folder workflow-multibranch git \
    pipeline-stage-view junit uno-choice credentials plain-credentials pipeline-graph-view

COPY --chown=jenkins:jenkins casc.yaml /usr/share/jenkins/ref/casc.yaml
COPY --chown=jenkins:jenkins init-token.groovy /usr/share/jenkins/ref/init.groovy.d/init-token.groovy
COPY --chown=jenkins:jenkins init-approve.groovy /usr/share/jenkins/ref/init.groovy.d/init-approve.groovy

ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc.yaml
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true"
