#!/usr/bin/env sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TEST_OUTPUT=$(mktemp -d "${TMPDIR:-/tmp}/shengjian-tests.XXXXXX")
trap 'rm -rf "$TEST_OUTPUT"' EXIT
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
    JAVAC_BIN="$JAVA_HOME/bin/javac"
else
    JAVA_BIN=java
    JAVAC_BIN=javac
fi
"$JAVAC_BIN" -encoding UTF-8 -d "$TEST_OUTPUT" \
    "$PROJECT_DIR/app/src/main/java/com/shengjian/tts/TextChunks.java" \
    "$PROJECT_DIR/app/src/main/java/com/shengjian/tts/WaveFiles.java" \
    "$PROJECT_DIR/tests/CoreTests.java"
"$JAVA_BIN" -cp "$TEST_OUTPUT" com.shengjian.tts.CoreTests
