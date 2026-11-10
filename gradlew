#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VERSION=8.7
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-$VERSION-bin/local"
ZIP="$CACHE/gradle-$VERSION-bin.zip"
DIST="$CACHE/gradle-$VERSION"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$CACHE"
  URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
  echo "Downloading Gradle $VERSION..."
  if command -v curl >/dev/null 2>&1; then curl -fL "$URL" -o "$ZIP"; else wget -O "$ZIP" "$URL"; fi
  rm -rf "$DIST"
  unzip -q "$ZIP" -d "$CACHE"
fi
exec "$DIST/bin/gradle" -p "$APP_HOME" "$@"
