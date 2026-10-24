#!/usr/bin/env bash
# Testira mrežnu jezgru Android aplikacije (com.litedrop.core) na JVM-u
# protiv stvarnog Litedrop PC servera. Ne treba Android SDK.
# Upotreba: ./run_core_test.sh [putanja-do-litedrop-binarke-za-ovaj-OS]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BIN="${1:-$HERE/../../litedrop/build/litedrop-linux}"
PORT=53600
T="$(mktemp -d)"

export LITEDROP_DIR="$T/cfg" LITEDROP_RECV="$T/recv" LITEDROP_PORT="$PORT"

"$BIN" serve &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true; rm -rf "$T"' EXIT
sleep 1

javac --release 17 -encoding UTF-8 -d "$T/classes" \
  $(find "$HERE/../app/src/main/java/com/litedrop/core" -name '*.java') "$HERE/CoreTest.java"
LC_ALL=C.UTF-8 java -Dfile.encoding=UTF-8 -cp "$T/classes" CoreTest "$BIN" "$PORT" "$LITEDROP_DIR" "$LITEDROP_RECV"
