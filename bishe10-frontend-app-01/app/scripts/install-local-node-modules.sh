#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
LOCAL_WORKSPACE="${HOME}/.codex-local-builds/bishe10-demo-uniapp/workspace"

sh "${ROOT_DIR}/scripts/use-local-node-modules.sh"
cd "${LOCAL_WORKSPACE}"
npm install --ignore-scripts
sh "./scripts/unquarantine-native-modules.sh"
