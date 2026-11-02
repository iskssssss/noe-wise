#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

pnpm --filter frontend-admin list --depth Infinity --json > frontend-admin/package-sbom.json
pnpm audit --audit-level high
