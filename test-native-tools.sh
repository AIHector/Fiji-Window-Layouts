#!/bin/sh
# Isolated ImageJ with candidate plugins. No physical input or user preferences are changed.
# Usage: ./test-native-tools.sh [Fiji-folder] [Undo_History.jar] [Side_Toolbar.jar]
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" "${1:-}"
UNDO="${2:-$FIJI/plugins/Undo_History.jar}"
SIDE="${3:-$FIJI/plugins/Side_Toolbar.jar}"
OUT="$HERE/build/native-tool-test-classes"
mkdir -p "$OUT" "$HERE/build/native-tool-test-home"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ" -d "$OUT" "$HERE"/src/fijilayouts/*.java "$HERE/test/fijilayouts/NativeToolWindowTests.java"
"$JAVA_BIN/java" -Duser.home="$HERE/build/native-tool-test-home" -Dapple.awt.UIElement=true -Xmx512m -cp "$OUT:$IJ:$UNDO:$SIDE" fijilayouts.NativeToolWindowTests
