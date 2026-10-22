#!/bin/sh
# Compile the plugin and run its headless unit tests (test/fijilayouts/LayoutTests.java).
# Usage: ./test.sh [/path/to/Fiji]   (default: /Applications/Fiji)
set -e
FIJI="${1:-/Applications/Fiji}"
HERE=$(cd "$(dirname "$0")" && pwd)
JAVA_BIN=$(echo "$FIJI"/java/macos-*/*/bin)
IJ=$(ls "$FIJI"/jars/ij-*.jar | head -1)
OUT="$HERE/build/test-classes"

rm -rf "$OUT" && mkdir -p "$OUT"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -cp "$IJ" -d "$OUT" "$HERE"/src/fijilayouts/*.java "$HERE"/test/fijilayouts/*.java
"$JAVA_BIN/java" -Djava.awt.headless=true -cp "$OUT:$IJ" fijilayouts.LayoutTests
