#!/usr/bin/env bash
#
# Build a native, double-clickable AIBRAIN desktop bundle with jpackage.
#
# jpackage ships inside JDK 14+ and produces a package for the OS it runs on,
# with its own Java runtime embedded — the user does NOT need Java installed.
# Run this on each OS you want to ship:
#
#   Linux  -> .rpm (needs rpmbuild) or .deb (needs dpkg-deb), else a raw app-image
#   macOS  -> .dmg
#   Windows-> .exe (needs WiX Toolset v3 on PATH), else a raw app-image
#
# Usage:  desktop/package.sh            # builds the fat jar, then the bundle
#         desktop/package.sh --no-build # skip the Maven build, reuse target/
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

APP_NAME="AIBRAIN"
APP_VERSION="0.1.0"
MAIN_JAR="aibrain-desktop.jar"
MAIN_CLASS="com.aibrain.desktop.Launcher"
DEST="target/dist"
STAGE="target/jpackage-input"

if [[ "${1:-}" != "--no-build" ]]; then
    echo ">> building fat jar (mvn package)…"
    mvn -q package -DskipTests
fi

if [[ ! -f "target/$MAIN_JAR" ]]; then
    echo "!! target/$MAIN_JAR missing — run without --no-build first" >&2
    exit 1
fi

# jpackage's --input must hold ONLY the jars that go into the app, so stage the
# fat jar on its own.
rm -rf "$STAGE" "$DEST"
mkdir -p "$STAGE" "$DEST"
cp "target/$MAIN_JAR" "$STAGE/"

# Non-modular (classpath) app: bundle a runtime with every JDK module, so no
# transitive dependency of JavaFX or sqlite-jdbc can be missing at runtime. The
# image can be slimmed later by replacing this with an explicit jlink module list.
MODS="$(java --list-modules | sed 's/@.*//' | paste -sd, -)"

# Pick the installer type for this OS, with a safe fallback to a raw app-image.
case "$(uname -s)" in
    Linux)
        if command -v rpmbuild >/dev/null 2>&1;  then TYPE="rpm"
        elif command -v dpkg-deb >/dev/null 2>&1; then TYPE="deb"
        else TYPE="app-image"; fi
        EXTRA=(--linux-shortcut) ;;
    Darwin)
        TYPE="dmg";  EXTRA=() ;;
    MINGW*|MSYS*|CYGWIN*)
        if command -v candle >/dev/null 2>&1; then TYPE="exe"; else TYPE="app-image"; fi
        EXTRA=(--win-shortcut --win-menu) ;;
    *)
        TYPE="app-image"; EXTRA=() ;;
esac

echo ">> jpackage --type $TYPE  (this embeds a Java runtime; takes a minute)…"
jpackage \
    --type "$TYPE" \
    --name "$APP_NAME" \
    --app-version "$APP_VERSION" \
    --input "$STAGE" \
    --main-jar "$MAIN_JAR" \
    --main-class "$MAIN_CLASS" \
    --add-modules "$MODS" \
    --vendor "AIBRAIN" \
    --dest "$DEST" \
    "${EXTRA[@]}"

echo ">> done. Bundle(s) in $HERE/$DEST:"
ls -la "$DEST"
