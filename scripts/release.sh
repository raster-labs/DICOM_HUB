#!/usr/bin/env bash
# =============================================================================
# DICOM Hub – Release Management Script
# Usage: ./scripts/release.sh v1.0.0 ["Optional release description"]
# Creates an annotated git tag, pushes main + tag to GitHub, and triggers
# the automated GitHub Actions Docker release pipeline.
# =============================================================================
set -euo pipefail

VERSION="${1:-}"
MESSAGE="${2:-DICOM Hub Release ${VERSION}}"

if [[ -z "${VERSION}" ]]; then
  echo "Usage: ./scripts/release.sh <vX.Y.Z> [\"Release description\"]"
  echo "Example: ./scripts/release.sh v1.0.0 \"Initial production release\""
  exit 1
fi

if [[ ! "${VERSION}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$ ]]; then
  echo "Error: Version tag must follow semantic versioning starting with 'v' (e.g. v1.0.0)"
  exit 1
fi

echo "=== DICOM Hub Release Manager (${VERSION}) ==="

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Staging and committing pending changes before tagging ${VERSION}..."
  git add -A
  git commit -m "chore(release): prepare release ${VERSION}"
fi

if git rev-parse "${VERSION}" >/dev/null 2>&1; then
  echo "Tag ${VERSION} already exists locally. Recreating tag at HEAD..."
  git tag -d "${VERSION}"
fi

git tag -a "${VERSION}" -m "${MESSAGE}"
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"

echo "Pushing branch '${CURRENT_BRANCH}' and tag '${VERSION}' to origin..."
git push origin "${CURRENT_BRANCH}"
git push origin "${VERSION}" --force

echo ""
echo "Release ${VERSION} pushed to GitHub!"
echo "GitHub Actions will now build the Docker images and publish the release bundle."
