ARG VERSION=2.169

FROM jenkins/jenkins:${VERSION}-slim

ARG VERSION

USER root

# ensure jenkins has access to docker from within docker
RUN groupadd -g 1001 docker && usermod -aG docker jenkins

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && \
    apt-get install -y \
      apt-transport-https \
      ca-certificates \
      curl \
      gnupg2 \
      postfix \
      socat \
      software-properties-common \
      sudo \
    && \
    ( curl -fsSL https://download.docker.com/linux/debian/gpg | apt-key add - ) && \
    add-apt-repository \
      "deb [arch=amd64] https://download.docker.com/linux/debian $(lsb_release -cs) stable" && \
    apt-get update && \
    apt-get install -y docker-ce-cli && \
    rm -rf /var/lib/apt/lists/*

USER jenkins

RUN echo $VERSION > /usr/share/jenkins/ref/jenkins.install.UpgradeWizard.state
RUN echo $VERSION > /usr/share/jenkins/ref/jenkins.install.InstallUtil.lastExecVersion

COPY systemtests/jenkins/plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN /usr/local/bin/install-plugins.sh < /usr/share/jenkins/ref/plugins.txt

COPY systemtests/jenkins/ref/* /usr/share/jenkins/ref/
COPY systemtests/jenkins/init.groovy.d/*.groovy /usr/share/jenkins/ref/init.groovy.d/

USER root

# install a custom entrypoint that can ensure jenkins has access to docker via
# a socket proxy
COPY systemtests/jenkins/bin/start-docker-proxy /usr/local/bin/start-docker-proxy
COPY systemtests/jenkins/bin/entrypoint /usr/local/bin/entrypoint

# make test repo and the local copy of pipelinelib accessible as a plugin via git
COPY systemtests/jenkins/bin/stub-script /usr/local/bin/docker-pusher
COPY systemtests/jenkins/sudoers /etc/sudoers.d/jenkins

RUN git config --global user.name docker && \
    git config --global user.email docker@docker.invalid

COPY systemtests/repo1 /var/lib/git/repo1
RUN cd /var/lib/git/repo1 && \
    git init . && \
    git add --all && \
    git commit -m repo1

COPY . /var/lib/git/pipelinelib
RUN cd /var/lib/git/pipelinelib && \
    git checkout -b test-in-docker-$VERSION && \
    test -z "$(git status -s)" || ( \
      git add --all && \
      git commit -m wip \
    ) && \
    git checkout master && \
    git merge -s octopus test-in-docker-$VERSION

USER jenkins

ENTRYPOINT /usr/local/bin/entrypoint
