#!/bin/sh
# Build locally by default. Installation requires the explicit --install flag with Fiji closed.
# Usage: ./build.sh [/path/to/Fiji] [--no-install|--install]
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
FIJI_ARG=""; INSTALL=false
for arg in "$@"; do
  case "$arg" in
    --install) INSTALL=true ;;
    --no-install) INSTALL=false ;;
    --*) echo "Unknown option: $arg" >&2; exit 1 ;;
    *) FIJI_ARG="$arg" ;;
  esac
done
. "$HERE/fiji-env.sh" "$FIJI_ARG"
VERSION=$(cat "$HERE/VERSION")
rm -rf "$HERE/build/classes"
mkdir -p "$HERE/build/classes"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ" -d "$HERE/build/classes" "$HERE"/src/fijilayouts/*.java
cp "$HERE/src/plugins.config" "$HERE/build/classes/"
printf 'Implementation-Title: Fiji Window Layouts\nImplementation-Version: %s\n' "$VERSION" > "$HERE/build/manifest.txt"
"$JAVA_BIN/jar" cfm "$HERE/build/Fiji_Window_Layouts.jar" "$HERE/build/manifest.txt" -C "$HERE/build/classes" .
echo "Built $HERE/build/Fiji_Window_Layouts.jar (version $VERSION)"
if [ "$INSTALL" = true ]; then
  cp "$HERE/build/Fiji_Window_Layouts.jar" "$FIJI/plugins/Fiji_Window_Layouts.jar"
  mkdir -p "$FIJI/macros/AutoRun"
  cp "$HERE/src/Window_Layouts_Startup.ijm" "$FIJI/macros/AutoRun/Window_Layouts_Startup.ijm"
  echo "Installed into $FIJI — restart Fiji."
fi
