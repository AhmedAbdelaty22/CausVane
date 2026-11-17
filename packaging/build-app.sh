#!/bin/sh
# Builds a native LogReader application (with its own name, icon and bundled Java runtime) for the
# operating system this script runs on. Run from the project root after "mvn clean package":
#
#   sh packaging/build-app.sh            macOS: dist/LogReader.app     Linux: dist/LogReader/
#   sh packaging/build-app.sh dmg        macOS installer image (also: pkg, deb, rpm)
set -e
cd "$(dirname "$0")/.."

TYPE="${1:-app-image}"
JAR=target/LogReader.jar
[ -f "$JAR" ] || { echo "Build the JAR first: mvn clean package" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) ICON=packaging/LogReader.icns ;;
  *)      ICON=packaging/LogReader.png ;;
esac

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp "$JAR" "$STAGE/"
rm -rf dist/LogReader.app dist/LogReader
mkdir -p dist

jpackage \
  --type "$TYPE" \
  --name LogReader \
  --app-version 2.1.0 \
  --description "Finds, explains and pinpoints errors in application logs" \
  --vendor "LogReader" \
  --icon "$ICON" \
  --input "$STAGE" \
  --main-jar LogReader.jar \
  --add-modules java.base,java.desktop,java.logging,java.sql \
  --java-options "--enable-native-access=ALL-UNNAMED" \
  --java-options "-Xmx1g" \
  --dest dist

# macOS: Finder caches app icons by path. After a rebuild at the same path it can show a blank
# placeholder until the bundle's date changes and it is registered again.
if [ "$(uname -s)" = "Darwin" ] && [ -d dist/LogReader.app ]; then
  touch dist/LogReader.app
  /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f dist/LogReader.app 2>/dev/null || true
fi

echo "Done. Output is in: $(pwd)/dist"
