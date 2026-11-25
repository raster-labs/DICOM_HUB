#!/usr/bin/env bash
set -euo pipefail

SCENARIO="${1:-ct-kub}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"

echo "=== Running DICOM C-STORE Simulator (scenario: ${SCENARIO}) ==="
cd "${REPO_ROOT}/backend"
mvn test -Dtest="DicomCStoreWorkflowTest" -Ddicom.scenario="${SCENARIO}"
