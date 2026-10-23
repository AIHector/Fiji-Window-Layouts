#!/bin/sh
# Build Fiji_Window_Layouts.jar from src/ and install it into Fiji.
# Usage: ./build.sh [/path/to/Fiji]   (default: /Applications/Fiji)
set -e
FIJI="${1:-/Applications/Fiji}"
HERE=$(cd "$(dirname "$0")" && pwd)
JAVA_BIN=$(echo "$FIJI"/java/macos-*/*/bin)
IJ=$(ls "$FIJI"/jars/ij-*.jar | head -1)

rm -rf "$HERE/build" && mkdir -p "$HERE/build/classes"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -cp "$IJ" -d "$HERE/build/classes" "$HERE"/src/fijilayouts/*.java
cp "$HERE/src/plugins.config" "$HERE/build/classes/"
"$JAVA_BIN/jar" cf "$HERE/build/Fiji_Window_Layouts.jar" -C "$HERE/build/classes" .
cp "$HERE/build/Fiji_Window_Layouts.jar" "$FIJI/plugins/Fiji_Window_Layouts.jar"
mkdir -p "$FIJI/macros/AutoRun"
cp "$HERE/src/Window_Layouts_Startup.ijm" "$FIJI/macros/AutoRun/Window_Layouts_Startup.ijm"
echo "Installed $FIJI/plugins/Fiji_Window_Layouts.jar and macros/AutoRun/Window_Layouts_Startup.ijm - restart Fiji."
