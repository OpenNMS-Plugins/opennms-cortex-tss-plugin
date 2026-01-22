###
# Makefile to build the Cortex time series plugin from source
##

.DEFAULT_GOAL := cortex-tss-plugin

SHELL               := /bin/bash -o pipefail -o errexit
VERSION             ?= $(shell mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
GIT_BRANCH          := $(shell git branch --show-current)
GIT_SHORT_HASH      := $(shell git rev-parse --short HEAD)
DATE                := $(shell date -u +"%Y-%m-%dT%H:%M:%SZ") # Date format RFC3339
JAVA_MAJOR_VERSION  := 17
MAVEN_ARGS          := --batch-mode -DupdatePolicy=never -Djava.awt.headless=true -Dstyle.color=always
MAVEN_SETTINGS_XML  := .circleci/.circleci.settings.xml

ARTIFACTS_DIR       := ./target/artifacts
RELEASE_VERSION     := UNSET.0.0
RELEASE_BRANCH      := main
MAJOR_VERSION       := $(shell echo $(RELEASE_VERSION) | cut -d. -f1)
MINOR_VERSION       := $(shell echo $(RELEASE_VERSION) | cut -d. -f2)
PATCH_VERSION       := $(shell echo $(RELEASE_VERSION) | cut -d. -f3)
SNAPSHOT_VERSION    := $(MAJOR_VERSION).$(MINOR_VERSION).$(shell expr $(PATCH_VERSION) + 1)-SNAPSHOT
RELEASE_LOG         := $(ARTIFACTS_DIR)/release.log
OK                  := "[ 👍 ]"

.PHONY: help
help:
	@echo ""
	@echo "Build the Cortex time series plugin from source"
	@echo ""
	@echo "Requirements to build:"
	@echo "  * OpenJDK 17 Development Kit"
	@echo "  * Maven"
	@echo ""
	@echo "We are using the command tool to test for the requirements in your search path."
	@echo ""
	@echo "Build targets:"
	@echo "  help:                  Show this help"
	@echo "  cortex-tss-plugin:     Compile the plugin from source code"
	@echo "  integration-tests:     Run full integration test suite"
	@echo "  clean:                 Clean the compile and package assemblies"
	@echo "  release:               Create a release in the local repository, e.g. make release RELEASE_VERSION=x.y.z"
	@echo "  maven-deploy:          Deploy Maven artifacts to a Maven repository, see $(MAVEN_SETTINGS_XML)"
	@echo "  libyear:               Analyze dependency age measured in libyears."
	@echo ""

.PHONY: deps-build
deps-build:
	@echo -n "👮‍♀️ Create artifact directory:   "
	@mkdir -p $(ARTIFACTS_DIR)
	@echo $(OK)
	@echo -n "👮‍♀️ Check Java runtime:          "
	@command -v java > /dev/null
	@echo $(OK)
	@echo -n "👮‍♀️ Check Java compiler:         "
	@command -v javac > /dev/null
	@echo $(OK)
	@echo -n "👮‍♀️ Check Maven binary:          "
	@command -v mvn > /dev/null
	@echo $(OK)
	@echo -n "👮‍♀️ Check Java version $(JAVA_MAJOR_VERSION):       "
	@java --version | grep '$(JAVA_MAJOR_VERSION)\.[[:digit:]]*\.[[:digit:]]*' >/dev/null
	@echo $(OK)
	@echo -n "👮‍♀️ Validate Maven project:      "
	@mvn validate > /dev/null
	@echo $(OK)

.PHONY: deps-it
deps-it:
	@echo -n "👮‍♀️ Check Docker is installed    "
	@command -v docker > /dev/null
	@echo $(OK)
	@echo -n "👮‍♀️ Check Docker is running      "
	@docker ps > /dev/null
	@echo $(OK)

.PHONY: cortex-tss-plugin
cortex-tss-plugin: deps-build
	mvn $(MAVEN_ARGS) install -DskipTests=true -DskipITs=true 2>&1 | tee $(ARTIFACTS_DIR)/mvn.compile.log

.PHONY: integration-tests
integration-tests: deps-build deps-it
	mvn $(MAVEN_ARGS) -DskipITs=false -DskipTests=false install test integration-test

.PHONY: collect-artifacts
collect-artifacts:
	find . -type f -regex ".*\/assembly\/kar\/target\/opennms-cortex-tss-plugin\.kar" -exec cp {} $(ARTIFACTS_DIR) \;
	echo $(VERSION) > $(ARTIFACTS_DIR)/pom-version.txt
	shasum -a 256 -b $(ARTIFACTS_DIR)/opennms-cortex-tss-plugin.kar > $(ARTIFACTS_DIR)/shasum256.txt
	cd $(ARTIFACTS_DIR); tar czf opennms-cortex-tss-plugin.tar.gz opennms-cortex-tss-plugin.kar shasum256.txt
	shasum -a 256 -b $(ARTIFACTS_DIR)/opennms-cortex-tss-plugin.tar.gz > $(ARTIFACTS_DIR)/opennms-cortex-tss-plugin.sha256

.PHONY: clean
clean: deps-build
	mvn clean

.PHONY: release
release: deps-build
	@mkdir -p target
	@echo ""
	@echo "Release version:                $(RELEASE_VERSION)"
	@echo "New snapshot version:           $(SNAPSHOT_VERSION)"
	@echo "Git version tag:                v$(RELEASE_VERSION)"
	@echo "Current branch:                 $(GIT_BRANCH)"
	@echo "Release branch:                 $(RELEASE_BRANCH)"
	@echo "Release log file:               $(RELEASE_LOG)"
	@echo ""
	@echo -n "👮‍♀️ Check release branch:        "
	@if [ "$(GIT_BRANCH)" != "$(RELEASE_BRANCH)" ]; then echo "Releases are made from the $(RELEASE_BRANCH) branch, your branch is $(GIT_BRANCH)."; exit 1; fi
	@echo "$(OK)"
	@echo -n "👮‍♀️ Check uncommited changes     "
	@if git status --porcelain | grep -q .; then echo "There are uncommited changes in your repository."; exit 1; fi
	@echo "$(OK)"
	@echo -n "👮‍♀️ Check branch in sync         "
	@if [ "$(git rev-parse HEAD)" != "$(git rev-parse @{u})" ]; then echo "$(RELEASE_BRANCH) branch not in sync with remote origin."; exit 1; fi
	@echo "$(OK)"
	@echo -n "👮‍♀️ Check release version:       "
	@if [ "$(RELEASE_VERSION)" = "UNSET.0.0" ]; then echo "Set a release version, e.g. make release RELEASE_VERSION=1.0.0"; exit 1; fi
	@echo "$(OK)"
	@echo -n "👮‍♀️ Check version tag available: "
	@if git rev-parse v$(RELEASE_VERSION) >$(RELEASE_LOG) 2>&1; then echo "Tag v$(RELEASE_VERSION) already exists"; exit 1; fi
	@echo "$(OK)"
	@echo -n "💅 Set Maven release version:   "
	@mvn versions:set -DnewVersion=$(RELEASE_VERSION) >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "👮‍♀️ Validate build:              "
	@$(MAKE) cortex-tss-plugin >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "🎁 Git commit new release       "
	@git commit --signoff -am "release: Cortex time series plugin version $(RELEASE_VERSION)" >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "🦄 Set Git version tag:         "
	@git tag -a "v$(RELEASE_VERSION)" -m "Release Cortex time series plugin version $(RELEASE_VERSION)" >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "⬆️ Set Maven snapshot version:  "
	@mvn versions:set -DnewVersion=$(SNAPSHOT_VERSION) >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo -n "🎁 Git commit snapshot release: "
	@git commit --signoff -am "release: Cortex time series plugin version $(SNAPSHOT_VERSION)" >>$(RELEASE_LOG) 2>&1
	@echo "$(OK)"
	@echo ""
	@echo "🦄 Congratulations! ✨"
	@echo "You made a release in your local repository."
	@echo "Publish the release by pushing the version tag"
	@echo "and the new snapshot version to the remote repo"
	@echo "with the following commands:"
	@echo ""
	@echo "  git push"
	@echo "  git push origin v$(RELEASE_VERSION)"
	@echo ""
	@echo "Thank you for computing with us."
	@echo ""

.PHONY: maven-deploy
maven-deploy: deps-build
	@echo "Deploy with Maven"
	mvn $(MAVEN_ARGS) -s $(MAVEN_SETTINGS_XML) -Prelease -DskipTests=true -Dmaven.verify.skip=true -Dmaven.install.skip=true deploy

.PHONY: libyear
libyear: deps-build
	@echo "Analyze dependency freshness measured in libyear"
	@mkdir -p $(ARTIFACTS_DIR)/logs
	mvn $(MAVEN_ARGS) io.github.mfoo:libyear-maven-plugin:analyze 2>&1 | tee $(ARTIFACTS_DIR)/logs/libyear.log
