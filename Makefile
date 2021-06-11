SHELL := /bin/bash
GRADLE := $(shell command -v gradle)
BLUBBER := $(shell command -v blubber)
DOCKER := $(shell command -v docker)

DOCKER_TAG := pipelinelib-tests-$(shell date -u +%Y%m%d-%H%M%S)
DOCKER_LABEL := wmf.gc=pipelinelib-tests
DOCKER_BUILD := docker build --label $(DOCKER_LABEL) --tag $(DOCKER_TAG)
DOCKER_RUN := docker run --rm --label $(DOCKER_LABEL) --name $(DOCKER_TAG)
DOCKER_STOP := docker stop "$(DOCKER_TAG)"
DOCKER_STOP_ALL = docker stop $(shell docker ps -qf label=$(DOCKER_LABEL))
DOCKER_RMI = docker rmi $(shell docker images -qf label=$(DOCKER_LABEL))

ifneq (,$(and $(DOCKER), $(DOCKER_HOST)))
	$(eval JENKINS_HOST := $(patsubst tcp://%,%,$(DOCKER_HOST)))
	$(eval JENKINS_HOST := $(word 1, $(subst :, ,$(JENKINS_HOST))))
endif

JENKINS_HOST ?= localhost
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
	gradle test
	@exit 0
else ifneq (,$(and $(BLUBBER), $(DOCKER)))
	blubber .pipeline/blubber.yaml test | docker build -t "$(DOCKER_TAG)" -f - .
	docker run --rm -it "$(DOCKER_TAG)"
	@exit 0
else
	@echo "Can't find Gradle or Blubber/Docker. Install one to run tests."
	@exit 1
endif

systemtest:
	$(eval JENKINS_URL := http://docker:docker@$(JENKINS_HOST):8080)
	$(eval JENKINS_BLUE_URL := $(JENKINS_URL)/blue/organizations/jenkins)
	$(eval BUILD_OUTPUT := $(shell mktemp -t pipelinelib-systemtest.XXXXXX))

	$(DOCKER_BUILD) -f systemtests/jenkins/Dockerfile .
	$(DOCKER_RUN) -d \
	  -p 8080:8080 \
	  -v /var/run/docker.sock:/var/run/docker.host.sock \
	  $(DOCKER_TAG)

	@while ! curl -s http://$(JENKINS_HOST):8080/ > /dev/null; do \
	  echo "waiting for jenkins..."; \
	  sleep 1; \
	done
	@while curl -s http://$(JENKINS_HOST):8080/ | grep -q "is getting ready to work"; do \
	  echo "waiting for jenkins..."; \
	  sleep 1; \
	done

	curl -X POST $(JENKINS_URL)/job/repo1/buildWithParameters?PLIB_PIPELINE=$(TEST_PIPELINE)

	@echo "Build $(JENKINS_URL)/job/repo1/1 created"
	@while curl -sw %%{http_code} $(JENKINS_URL)/job/repo1/1/api/json | grep -q '404'; do \
	  echo "waiting for build to start..."; \
	  sleep 1; \
	done

	@while curl -s $(JENKINS_URL)/job/repo1/1/api/json | grep -q '"building":true'; do \
	  sleep 1; \
	  curl -s $(JENKINS_URL)/job/repo1/1/consoleText | \
	    tail -n +$$(wc -l $(BUILD_OUTPUT) | awk '{ print $$1 }') | \
	    tee -a $(BUILD_OUTPUT); \
	done

	rm -f $(BUILD_OUTPUT)

ifeq (1,$(DEBUG))
	@echo "DEBUG: Build $(JENKINS_URL)/job/repo1/1 completed"
	@echo -n "DEBUG: Press <enter> to continue: "
	@read
endif

	$(DOCKER_STOP)
