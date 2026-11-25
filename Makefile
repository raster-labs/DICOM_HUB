JAVA_HOME ?= /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
VERSION ?= v1.0.0

.PHONY: help healthcheck test-backend audit-deid sim-cstore build-backend install-frontend build-frontend run-lan docker-up docker-down release deploy-prod

help:
	@echo "DICOM Hub Developer & Release Commands:"
	@echo "  make healthcheck            - Verify Java 25, Maven, Node, and Docker toolchain"
	@echo "  make test-backend           - Run Spring Boot + dcm4che + Flyway JUnit 5 test suites"
	@echo "  make audit-deid             - Run DICOM PS3.15 & AC-2 privacy de-identification audit"
	@echo "  make sim-cstore             - Run end-to-end DICOM C-STORE workflow & cleanup simulation"
	@echo "  make build-backend          - Package Spring Boot backend executable JAR"
	@echo "  make install-frontend       - Install Angular 19 dependencies"
	@echo "  make build-frontend         - Build Angular 19 Command Centre portal"
	@echo "  make run-lan                - Build and start DICOM Hub bound to 0.0.0.0 for LAN access"
	@echo "  make docker-up              - Launch full Docker Compose stack"
	@echo "  make docker-down            - Stop Docker Compose stack"
	@echo "  make release VERSION=v1.0.0 - Tag and push a release to GitHub (triggers CI/CD Docker build)"
	@echo "  make deploy-prod            - Deploy production Docker stack on server (./scripts/deploy.sh)"

healthcheck:
	./.agents/skills/dev-env-orchestrator/scripts/dev_healthcheck.sh

test-backend:
	cd backend && JAVA_HOME=$(JAVA_HOME) mvn test

audit-deid:
	./.agents/skills/dicom-ps315-deid-audit/scripts/audit_deid_files.sh

sim-cstore:
	./.agents/skills/dicom-cstore-simulator/scripts/send_test_study.sh ct-kub

build-backend:
	cd backend && JAVA_HOME=$(JAVA_HOME) mvn clean package -DskipTests

install-frontend:
	cd frontend && npm install

build-frontend:
	cd frontend && npm run build

run-lan: build-frontend
	mkdir -p backend/src/main/resources/static
	cp -R frontend/dist/dicom-hub-command-centre/browser/* backend/src/main/resources/static/
	cd backend && JAVA_HOME=$(JAVA_HOME) mvn package -DskipTests
	cd backend && JAVA_HOME=$(JAVA_HOME) $(JAVA_HOME)/bin/java -jar target/dicom-hub-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=lan

docker-up:
	docker compose up --build -d

docker-down:
	docker compose down

release:
	./scripts/release.sh $(VERSION)

deploy-prod:
	./scripts/deploy.sh $(VERSION)
