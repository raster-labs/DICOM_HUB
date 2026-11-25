---
name: dev-env-orchestrator
description: >-
  Start, verify, and troubleshoot the local DICOM Hub development environment
  (Java 25 + Spring Boot + dcm4che backend, Angular 19 Command Centre portal,
  PostgreSQL 16, and Docker Compose stack).
---

# DICOM Hub Development Environment Orchestrator

Use this skill when starting local services, checking tool versions, running full-stack builds, or diagnosing Docker Compose / local port issues.

## Local Toolchain Paths on macOS

- **Java 25 LTS:** `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
- **Maven:** `/opt/homebrew/bin/mvn` (v3.10.0)
- **Node / npm:** `node` (v24.18.1), `npm` (v11.16.0)
- **Docker Compose:** `docker compose` (v5.3.1)

## Standard Commands

1. **Run Environment Health Check:**
   - Execute [dev_healthcheck.sh](./scripts/dev_healthcheck.sh)
2. **Backend Build & Test (with embedded H2 PostgreSQL-mode or Testcontainers/Postgres):**
   - `cd backend && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn clean test`
3. **Frontend Build & Type Check:**
   - `cd frontend && npm run build`
4. **Full Docker Compose Stack:**
   - `docker compose up --build -d`
