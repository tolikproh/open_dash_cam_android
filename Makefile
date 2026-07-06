JAVA_HOME ?= /usr/lib/jvm/java-21-openjdk-amd64
ANDROID_SDK ?= $(HOME)/Android/Sdk
GRADLEW := ./gradlew
GRADLE := JAVA_HOME=$(JAVA_HOME) $(GRADLEW)

APK_DEBUG := mobile/build/outputs/apk/debug/mobile-debug.apk
APK_RELEASE := mobile/build/outputs/apk/release/mobile-release.apk
KEYSTORE := release.keystore
KEYSTORE_PROPS := keystore.properties

VERSION := $(shell grep 'versionName' mobile/build.gradle | sed -n "s/.*versionName ['\"]\([^'\"]*\)['\"].*/\1/p" | head -1)
TAG := v$(VERSION)
APK_ASSET := open-dash-cam-$(TAG).apk
GITHUB_NOTES ?= Release $(VERSION)
GITHUB_REPO ?= $(shell git remote get-url origin 2>/dev/null | sed -E 's|.*github.com[:/]([^/]+/[^/.]+).*|\1|')
GH := gh --repo $(GITHUB_REPO)

.PHONY: all setup keystore debug release install publish clean help

all: debug

help:
	@echo "Open Dash Cam — цели сборки:"
	@echo "  make setup    — local.properties и права на gradlew"
	@echo "  make keystore — создать release.keystore для подписи APK"
	@echo "  make debug    — debug APK ($(APK_DEBUG))"
	@echo "  make release  — release APK ($(APK_RELEASE))"
	@echo "  make publish  — собрать release и опубликовать на GitHub (тег $(TAG))"
	@echo "  make install  — установить debug на подключённое устройство"
	@echo "  make clean    — очистить артефакты сборки"
	@echo ""
	@echo "Переменные: JAVA_HOME=$(JAVA_HOME), ANDROID_SDK=$(ANDROID_SDK), GITHUB_REPO=$(GITHUB_REPO)"

setup: local.properties
	@chmod +x $(GRADLEW)

local.properties:
	@echo "sdk.dir=$(ANDROID_SDK)" > local.properties

keystore:
	@test -f $(KEYSTORE) && { echo "Keystore уже существует: $(KEYSTORE)"; exit 1; } || true
	@KEYSTORE_PASS=$${KEYSTORE_PASS:-android}; \
	KEY_PASS=$${KEY_PASS:-android}; \
	keytool -genkeypair -v \
		-keystore $(KEYSTORE) \
		-alias opendashcam \
		-keyalg RSA -keysize 2048 -validity 10000 \
		-storepass "$$KEYSTORE_PASS" \
		-keypass "$$KEY_PASS" \
		-dname "CN=Open Dash Cam, OU=Mobile, O=OpenDashCam, L=Unknown, ST=Unknown, C=RU"
	@KEYSTORE_PASS=$${KEYSTORE_PASS:-android}; \
	KEY_PASS=$${KEY_PASS:-android}; \
	printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=opendashcam\nkeyPassword=%s\n' \
		"$(KEYSTORE)" "$$KEYSTORE_PASS" "$$KEY_PASS" > $(KEYSTORE_PROPS)
	@echo "Создан $(KEYSTORE) и $(KEYSTORE_PROPS) (пароль по умолчанию: android)"

debug: setup
	$(GRADLE) assembleDebug
	@echo "APK: $(APK_DEBUG)"

release: setup
	$(GRADLE) assembleRelease
	@echo "APK: $(APK_RELEASE)"

publish: release
	@command -v gh >/dev/null || { echo "Установите GitHub CLI: sudo apt install gh && gh auth login"; exit 1; }
	@test -n "$(GITHUB_REPO)" || { echo "Не удалось определить репозиторий из git remote; задайте GITHUB_REPO=owner/repo"; exit 1; }
	@test -n "$(VERSION)" || { echo "Не удалось прочитать versionName из mobile/build.gradle"; exit 1; }
	@test -f $(APK_RELEASE) || { echo "APK не найден: $(APK_RELEASE)"; exit 1; }
	@cp $(APK_RELEASE) $(APK_ASSET)
	@if $(GH) release view $(TAG) >/dev/null 2>&1; then \
		echo "Релиз $(TAG) уже существует, загружаю APK в $(GITHUB_REPO)..."; \
		$(GH) release upload $(TAG) $(APK_ASSET) --clobber; \
	else \
		echo "Создаю релиз $(TAG) в $(GITHUB_REPO)..."; \
		$(GH) release create $(TAG) $(APK_ASSET) --title "$(TAG)" --notes "$(GITHUB_NOTES)"; \
	fi
	@rm -f $(APK_ASSET)
	@echo "GitHub release: $$($(GH) release view $(TAG) --json url -q .url)"

install: setup
	$(GRADLE) installDebug

clean: setup
	$(GRADLE) clean
