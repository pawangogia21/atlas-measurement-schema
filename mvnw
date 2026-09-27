#!/bin/sh
set -e
MAVEN_VERSION=3.9.11
MAVEN_USER_HOME=${MAVEN_USER_HOME:-"$HOME/.m2"}
MAVEN_HOME="$MAVEN_USER_HOME/wrapper/dists/apache-maven-$MAVEN_VERSION/apache-maven-$MAVEN_VERSION"
MAVEN_BINARY="$MAVEN_HOME/bin/mvn"
MAVEN_ARCHIVE_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MAVEN_VERSION/apache-maven-$MAVEN_VERSION-bin.zip"
if [ ! -x "$MAVEN_BINARY" ]; then
  mkdir -p "$MAVEN_USER_HOME/wrapper/dists/apache-maven-$MAVEN_VERSION"
  TEMP_DIR=$(mktemp -d "$MAVEN_USER_HOME/wrapper/dists/.maven-wrapper.XXXXXX")
  trap 'rm -rf "$TEMP_DIR"' EXIT
  curl -fsSL "$MAVEN_ARCHIVE_URL" -o "$TEMP_DIR/apache-maven.zip"
  unzip -q "$TEMP_DIR/apache-maven.zip" -d "$TEMP_DIR"
  mv "$TEMP_DIR/apache-maven-$MAVEN_VERSION" "$MAVEN_HOME"
fi
exec "$MAVEN_BINARY" "$@"
