#!/bin/sh
# Builds CausVane for the operating system this script runs on: the app with its own name, icon and
# bundled Java, or an installer for it. GitHub's build runs it on its macOS and Linux machines for every
# release (.github/workflows/build.yml); it is not meant to be run on your own computer.
#
#   sh packaging/build-app.sh            the app on its own   (macOS: dist/CausVane.app, Linux: dist/CausVane/)
#   sh packaging/build-app.sh dmg        macOS installer: a disk image with the app and an Applications shortcut
#   sh packaging/build-app.sh deb        Linux package for Debian and Ubuntu, with an applications-menu entry
#   sh packaging/build-app.sh rpm        Linux package for Fedora, RHEL and openSUSE, with a menu entry
#
# Run from the project root after the JAR is built (Maven's package phase).
set -e
cd "$(dirname "$0")/.."

TYPE="${1:-app-image}"
VERSION=2.2.0
JAR=target/CausVane.jar
[ -f "$JAR" ] || { echo "Build the JAR first (Maven package phase)" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) ICON=packaging/CausVane.icns ;;
  *)      ICON=packaging/CausVane.png ;;
esac

# What each kind of installer adds on top of the app itself
case "$TYPE" in
  app-image)
    EXTRA=""
    rm -rf dist/CausVane.app dist/CausVane ;;
  dmg|pkg)
    EXTRA="--mac-package-identifier io.causvane --mac-package-name CausVane" ;;
  deb)
    EXTRA="--linux-package-name causvane --linux-shortcut --linux-menu-group Development;Utility
           --linux-app-category devel --linux-deb-maintainer causvane@users.noreply.github.com
           --about-url https://github.com/AhmedAbdelaty22/CausVane" ;;
  rpm)
    EXTRA="--linux-package-name causvane --linux-shortcut --linux-menu-group Development;Utility
           --linux-app-category Development/Tools --about-url https://github.com/AhmedAbdelaty22/CausVane" ;;
  *)
    echo "Unknown type: $TYPE (use app-image, dmg, pkg, deb or rpm)" >&2; exit 2 ;;
esac

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp "$JAR" "$STAGE/"
mkdir -p dist

# shellcheck disable=SC2086
jpackage \
  --type "$TYPE" \
  --name CausVane \
  --app-version "$VERSION" \
  --description "Finds, explains and pinpoints errors in application logs" \
  --vendor "CausVane" \
  --copyright "CausVane contributors" \
  --icon "$ICON" \
  --input "$STAGE" \
  --main-jar CausVane.jar \
  --add-modules java.base,java.desktop,java.logging,java.sql \
  --java-options "--enable-native-access=ALL-UNNAMED" \
  --java-options "-Xmx1g" \
  $EXTRA \
  --dest dist

echo "Done. Output is in: $(pwd)/dist"
