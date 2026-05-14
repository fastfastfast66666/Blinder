#!/bin/sh

set -eu

if [ "$(uname -s)" != "Darwin" ]; then
  exit 0
fi

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
NODE_MODULES_DIR="$ROOT_DIR/node_modules"

if [ ! -d "$NODE_MODULES_DIR" ]; then
  exit 0
fi

find "$NODE_MODULES_DIR" -type f \( -name '*.node' -o -name 'esbuild' \) -print0 | while IFS= read -r -d '' file; do
  if ! file "$file" | grep -q 'Mach-O'; then
    continue
  fi
  xattr -d com.apple.quarantine "$file" >/dev/null 2>&1 || true
  xattr -d com.apple.provenance "$file" >/dev/null 2>&1 || true
  xattr -c "$file" >/dev/null 2>&1 || true
  codesign --force --sign - "$file" >/dev/null 2>&1 || true
done
