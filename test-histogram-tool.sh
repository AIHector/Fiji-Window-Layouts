#!/bin/sh
# Real menu + real B&C in an isolated ImageJ. No physical input or user settings.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" "${1:-}"
OUT="$HERE/build/histogram-tool-test-classes"
mkdir -p "$OUT" "$HERE/build/histogram-tool-test-home"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ" -d "$OUT" "$HERE"/src/fijilayouts/*.java "$HERE/test/fijilayouts/HistogramToolTests.java"
shift "$(( $# > 0 ? 1 : 0 ))"
"$JAVA_BIN/java" -Duser.home="$HERE/build/histogram-tool-test-home" -Dapple.awt.UIElement=true -Xmx768m -cp "$OUT:$IJ:$FIJI/plugins/Undo_History.jar:$FIJI/plugins/Side_Toolbar.jar" fijilayouts.HistogramToolTests "$HERE/build" "$@"
