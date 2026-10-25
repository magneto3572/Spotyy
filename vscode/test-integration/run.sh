#!/usr/bin/env bash
# Runs the end-to-end suite in a fresh, isolated VS Code instance.
#   SPOTYY_SKIP_SPOTIFY=1  skip tests that control the real Spotify app
#   SPOTYY_OUT=<dir>       where results.json, peer logs and screenshots go
#   VSCODE_BIN=<path>      VS Code binary to test with (default: code), e.g. an older release
set -euo pipefail
cd "$(dirname "$0")/.."
npm run build --silent
PROFILE="$(mktemp -d)"
# Distinct name so packets from a JetBrains IDE running Spotyy as the same OS user don't interfere
export SPOTYY_USERNAME="${SPOTYY_USERNAME:-vscode-e2e}"
trap 'rm -rf "$PROFILE"; docker rm -f spotyy-alice spotyy-bob >/dev/null 2>&1 || true' EXIT
"${VSCODE_BIN:-code}" --new-window --wait --disable-extensions --skip-welcome --skip-release-notes --disable-workspace-trust \
  --user-data-dir "$PROFILE/user" --extensions-dir "$PROFILE/ext" \
  --extensionDevelopmentPath="$PWD" \
  --extensionTestsPath="$PWD/test-integration/suite.js"
