#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
export PATH="${JAVA_HOME}/bin:${PATH}"

echo "=== DICOM Hub Development Environment Healthcheck ==="
echo -n "Java:    " && java -version 2>&1 | head -n 1
echo -n "Maven:   " && mvn -version | head -n 1
echo -n "Node:    " && node --version
echo -n "NPM:     " && npm --version
echo -n "Docker:  " && docker --version
if docker info >/dev/null 2>&1; then
  echo "Docker Daemon: RUNNING"
else
  echo "Docker Daemon: NOT RUNNING (start Docker Desktop before running 'docker compose up')"
fi
