SHELL := /bin/bash
GRADLE := $(shell command -v gradle)
GRADLE_FLAGS :=
DOCKER := $(shell command -v docker)

DOCKER_TAG := pipelinelib-tests-$(shell date -u +%Y%m%d-%H%M%S)
DOCKER_LABEL := wmf.gc=pipelinelib-tests
DOCKER_BUILD := docker build --label $(DOCKER_LABEL) --tag $(DOCKER_TAG)
DOCKER_RUN := docker run --rm --label $(DOCKER_LABEL) --name $(DOCKER_TAG)
DOCKER_STOP := docker stop "$(DOCKER_TAG)"
DOCKER_STOP_ALL = docker stop $(shell docker ps -qf label=$(DOCKER_LABEL))
DOCKER_RMI = docker rmi $(shell docker images -qf label=$(DOCKER_LABEL))
DOCKER_BUILDKIT := 1
export DOCKER_BUILDKIT

ifneq (,$(and $(DOCKER), $(DOCKER_HOST)))
	$(eval JENKINS_HOST := $(patsubst tcp://%,%,$(DOCKER_HOST)))
	$(eval JENKINS_HOST := $(word 1, $(subst :, ,$(JENKINS_HOST))))
endif

JENKINS_HOST ?= localhost
JENKINS_HOST_PORT ?= 8080
TEST_PIPELINE ?=

.PHONY: test

clean:
ifneq (,$(DOCKER))
	$(DOCKER_STOP_ALL) 2> /dev/null || true
	$(DOCKER_RMI) 2> /dev/null || true
else
	@echo "Not using Docker. Nothing to do."
endif

doc: docs
docs:
	gradle groovydoc

test:
ifneq (,$(GRADLE))
	$(GRADLE) test $(GRADLE_FLAGS)
	@exit 0
else ifneq (,$(DOCKER))
	docker build --target test -f .pipeline/blubber.yaml -t "$(DOCKER_TAG)" .
	docker run --rm -it "$(DOCKER_TAG)" $(GRADLE_FLAGS)
	@exit 0
else
	@echo "Can't find Gradle or Docker. Install one to run tests."
	@exit 1
endif

systemtest:
	$(eval JENKINS_URL := http://docker:docker@$(JENKINS_HOST):$(JENKINS_HOST_PORT))
	$(eval JENKINS_BLUE_URL := $(JENKINS_URL)/blue/organizations/jenkins)
	$(eval JENKINS_COOKIES := $(shell mktemp -t pipelinelib-systemtest.XXXXXX.cookies))
	$(eval BUILD_OUTPUT := $(shell mktemp -t pipelinelib-systemtest.XXXXXX))

	$(DOCKER_BUILD) -f systemtests/jenkins/Dockerfile .
	$(DOCKER_RUN) -d \
	  -p $(JENKINS_HOST_PORT):8080 \
	  -v /var/run/docker.sock:/var/run/docker.host.sock \
	  $(DOCKER_TAG)

	@while ! curl -s http://$(JENKINS_HOST):$(JENKINS_HOST_PORT)/ > /dev/null; do \
	  echo "waiting for jenkins..."; \
	  sleep 1; \
	done
	@while curl -s http://$(JENKINS_HOST):$(JENKINS_HOST_PORT)/ | grep -q "is getting ready to work"; do \
	  echo "waiting for jenkins..."; \
	  sleep 1; \
	done

	curl -X POST \
	  -H "Jenkins-Crumb: $$(curl -sc $(JENKINS_COOKIES) $(JENKINS_URL)/crumbIssuer/api/json | jq -r .crumb)" \
	  -b $(JENKINS_COOKIES) \
	  $(JENKINS_URL)/job/repo1/buildWithParameters?PLIB_PIPELINE=$(TEST_PIPELINE)

	@echo "Build $(JENKINS_URL)/job/repo1/1 created"
	@while curl -sw %%{http_code} $(JENKINS_URL)/job/repo1/1/api/json | grep -q '404'; do \
	  echo "waiting for build to start..."; \
	  sleep 1; \
	done

	@# FIXME: No console text will be seen at all if the job finishes building by
	@# the time we check its builing status here.
	@while curl -s $(JENKINS_URL)/job/repo1/1/api/json | grep -q '"building":true'; do \
	  sleep 1; \
	  curl -s $(JENKINS_URL)/job/repo1/1/consoleText | \
	    tail -n +$$(wc -l $(BUILD_OUTPUT) | awk '{ print $$1 }') | \
	    tee -a $(BUILD_OUTPUT); \
	done

	rm -f "$(BUILD_OUTPUT)"
	rm -f "$(JENKINS_COOKIES)"

ifeq (1,$(DEBUG))
	@echo "DEBUG: Build $(JENKINS_URL)/job/repo1/1 status: $$(curl -s $(JENKINS_URL)/job/repo1/1/api/json | jq -r .result)"
	@echo -n "DEBUG: Press <enter> to continue: "
	@read
else
	@# Verify that the build was successful.  Note that if this test fails, the
	@# container will remain running
	@if [ $$(curl -s $(JENKINS_URL)/job/repo1/1/api/json | jq -r .result) != "SUCCESS" ]; then \
	  echo "Build $(JENKINS_URL)/job/repo1/1 status: $$(curl -s $(JENKINS_URL)/job/repo1/1/api/json | jq -r .result)" ; \
	  false ; \
	fi
endif

	$(DOCKER_STOP)

# Kill all containers started by the systemtest target.  This approach is
# faster than $(DOCKER_STOP_ALL) (doesn't wait for 10 seconds)
kill:
	for id in $$(docker ps -q -f label=$(DOCKER_LABEL)); do docker rm -f $$id; done
