#!/bin/sh
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" "${1:-}"
UNDO="$FIJI/plugins/Undo_History.jar"
OUT="$HERE/build/combined-test-classes"
mkdir -p "$OUT" "$HERE/build/combined-home"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ:$UNDO" -d "$OUT" "$HERE"/src/fijilayouts/*.java "$HERE/test/fijilayouts/CombinedZoomUndoTest.java"
"$JAVA_BIN/java" -Duser.home="$HERE/build/combined-home" -Dapple.awt.UIElement=true -Xmx512m -cp "$OUT:$IJ:$UNDO" fijilayouts.CombinedZoomUndoTest
