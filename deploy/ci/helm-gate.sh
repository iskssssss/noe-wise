#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."
if ! command -v helm >/dev/null 2>&1; then
  echo "ERROR: helm is required for the Helm gate."
  exit 1
fi
VALUES=(--set secrets.existingSecret=ci-runtime)
helm lint deploy/helm/skillforge "${VALUES[@]}"
helm template skillforge deploy/helm/skillforge "${VALUES[@]}" > /dev/null
