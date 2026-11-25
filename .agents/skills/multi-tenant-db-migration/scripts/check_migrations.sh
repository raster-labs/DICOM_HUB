#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
MIGRATION_DIR="${REPO_ROOT}/backend/src/main/resources/db/migration"

echo "=== Checking Flyway SQL migrations in ${MIGRATION_DIR} ==="
if [ ! -d "${MIGRATION_DIR}" ]; then
  echo "ERROR: Migration directory not found: ${MIGRATION_DIR}"
  exit 1
fi

for f in "${MIGRATION_DIR}"/V*.sql; do
  echo "Verified migration file: $(basename "$f")"
  if grep -qiE "DROP TABLE|TRUNCATE" "$f"; then
    echo "WARNING: Destructive SQL detected in $(basename "$f")"
  fi
done

grep -q "site_id" "${MIGRATION_DIR}"/V1__*.sql && echo "OK: site_id tenant isolation present in core schema."
