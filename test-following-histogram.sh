#!/bin/sh
# Reusable tool and installed toolbar integration, with isolated image/preferences and no physical input.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" "${1:-}"
OUT="$HERE/build/following-histogram-test-classes"
mkdir -p "$OUT" "$HERE/build/following-histogram-test-home"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ" -d "$OUT" "$HERE"/src/fijilayouts/*.java "$HERE/test/fijilayouts/FollowingHistogramTests.java"
"$JAVA_BIN/java" -Duser.home="$HERE/build/following-histogram-test-home" -Dapple.awt.UIElement=true -Xmx768m -cp "$OUT:$IJ:$FIJI/plugins/Side_Toolbar.jar" fijilayouts.FollowingHistogramTests "$HERE/build"
