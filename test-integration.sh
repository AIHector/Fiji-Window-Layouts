#!/bin/sh
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" "${1:-}"
OUT="$HERE/build/integration-test-classes"
mkdir -p "$OUT" "$HERE/build/integration-home"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ" -d "$OUT" "$HERE"/src/fijilayouts/*.java "$HERE/test/fijilayouts/SmoothIntegrationTests.java"
"$JAVA_BIN/java" -Duser.home="$HERE/build/integration-home" -Dapple.awt.UIElement=true -Xmx1g -cp "$OUT:$IJ" fijilayouts.SmoothIntegrationTests "$HERE/build"
