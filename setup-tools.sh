#!/usr/bin/env bash
# Downloads the toolchain build.sh expects into $TOOL_ROOT (default ./tools).
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOL_ROOT="${TOOL_ROOT:-$PROJ/tools}"
mkdir -p "$TOOL_ROOT"
cd "$TOOL_ROOT"

fetch() {
    local url="$1" out="$2"
    [ -s "$out" ] && { echo "  have $out"; return; }
    echo "  fetching $out"
    curl -sSLf -o "$out" "$url"
}

echo "[1/3] libraries"
fetch https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar api-102.aar

echo "[2/3] android sdk pieces"
fetch https://dl.google.com/android/repository/build-tools_r34-linux.zip bt34.zip
fetch https://dl.google.com/android/repository/platform-34-ext7_r03.zip plat34.zip

echo "[3/3] unpack"
mkdir -p xapi bt plat
(cd xapi && unzip -o -q ../api-102.aar)
unzip -o -q bt34.zip -d bt
unzip -o -q plat34.zip -d plat
chmod +x bt/*/aapt2 bt/*/d8 bt/*/zipalign 2>/dev/null || true

echo "done. tools in $TOOL_ROOT"
