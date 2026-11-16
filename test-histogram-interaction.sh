#!/bin/sh
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" "${1:-}"
OUT="$HERE/build/histogram-interaction-classes"
mkdir -p "$OUT" "$HERE/build/histogram-interaction-home"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ" -d "$OUT" "$HERE"/src/fijilayouts/*.java "$HERE/test/fijilayouts/HistogramInteractionTests.java"
"$JAVA_BIN/java" -Duser.home="$HERE/build/histogram-interaction-home" -Dapple.awt.UIElement=true -Xmx512m -cp "$OUT:$IJ" fijilayouts.HistogramInteractionTests "$HERE/build"
