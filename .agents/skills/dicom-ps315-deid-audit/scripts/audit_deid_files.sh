#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"

echo "=== Running DICOM PS3.15 De-identification & AC-2 Privacy Audit ==="
cd "${REPO_ROOT}/backend"
mvn test -Dtest="DeidentificationPs315AuditTest"
