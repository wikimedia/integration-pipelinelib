SHELL := /bin/bash
GRADLE := $(shell command -v gradle)
BLUBBER := $(shell command -v blubber)
DOCKER := $(shell command -v docker)
DOCKER_TAG := piplinelib-tests-$(shell date -I)


.PHONY: test
test:
ifneq (,$(GRADLE))
	gradle test
	@exit 0
else ifneq (,$(and $(BLUBBER), $(DOCKER)))
	blubber .pipeline/blubber.yaml test | docker build -t "$(DOCKER_TAG)" -f - .
    docker run --rm -it "$(DOCKER_TAG)"
	docker rmi "$(DOCKER_TAG)"
	@exit 0
else
	@echo "Can't find Gradle or Blubber/Docker. Install one to run tests."
	@exit 1
endif
