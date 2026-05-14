#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
LOCAL_ROOT="${HOME}/.codex-local-builds/bishe10-demo-uniapp"
LOCAL_WORKSPACE="${LOCAL_ROOT}/workspace"
LOCAL_NODE_MODULES="${LOCAL_WORKSPACE}/node_modules"
PROJECT_NODE_MODULES="${ROOT_DIR}/node_modules"

mkdir -p "${LOCAL_WORKSPACE}/scripts"
cp "${ROOT_DIR}/package.json" "${LOCAL_WORKSPACE}/package.json"
[ -f "${ROOT_DIR}/package-lock.json" ] && cp "${ROOT_DIR}/package-lock.json" "${LOCAL_WORKSPACE}/package-lock.json" || true
cp "${ROOT_DIR}/scripts/unquarantine-native-modules.sh" "${LOCAL_WORKSPACE}/scripts/unquarantine-native-modules.sh"

if [ -L "${PROJECT_NODE_MODULES}" ]; then
  CURRENT_TARGET=$(readlink "${PROJECT_NODE_MODULES}" || true)
  if [ "${CURRENT_TARGET}" = "${LOCAL_NODE_MODULES}" ]; then
    exit 0
  fi
  rm -f "${PROJECT_NODE_MODULES}"
elif [ -d "${PROJECT_NODE_MODULES}" ]; then
  BACKUP_PATH="${ROOT_DIR}/node_modules.smb-backup.$(date +%s)"
  mv "${PROJECT_NODE_MODULES}" "${BACKUP_PATH}"
fi

mkdir -p "${LOCAL_NODE_MODULES}"
ln -s "${LOCAL_NODE_MODULES}" "${PROJECT_NODE_MODULES}"
