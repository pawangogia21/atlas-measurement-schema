#!/bin/sh
set -e
MAVEN_VERSION=3.9.11
# AT-16 S9: the distribution is verified before it is unpacked (the equivalent of distributionSha256Sum in a wrapper
# properties file, which this script-only wrapper does not have). sha512 of the same zip is published on Maven Central.
MAVEN_ARCHIVE_SHA256=0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb
MAVEN_USER_HOME=${MAVEN_USER_HOME:-"$HOME/.m2"}
MAVEN_HOME="$MAVEN_USER_HOME/wrapper/dists/apache-maven-$MAVEN_VERSION/apache-maven-$MAVEN_VERSION"
MAVEN_BINARY="$MAVEN_HOME/bin/mvn"
MAVEN_ARCHIVE_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MAVEN_VERSION/apache-maven-$MAVEN_VERSION-bin.zip"
if [ ! -x "$MAVEN_BINARY" ]; then
  mkdir -p "$MAVEN_USER_HOME/wrapper/dists/apache-maven-$MAVEN_VERSION"
  TEMP_DIR=$(mktemp -d "$MAVEN_USER_HOME/wrapper/dists/.maven-wrapper.XXXXXX")
  trap 'rm -rf "$TEMP_DIR"' EXIT
  curl -fsSL "$MAVEN_ARCHIVE_URL" -o "$TEMP_DIR/apache-maven.zip"
  if command -v sha256sum >/dev/null 2>&1; then ACTUAL_SHA256=$(sha256sum "$TEMP_DIR/apache-maven.zip" | cut -d" " -f1); else ACTUAL_SHA256=$(shasum -a 256 "$TEMP_DIR/apache-maven.zip" | cut -d" " -f1); fi
  if [ "$ACTUAL_SHA256" != "$MAVEN_ARCHIVE_SHA256" ]; then
    echo "mvnw: checksum mismatch for $MAVEN_ARCHIVE_URL (expected $MAVEN_ARCHIVE_SHA256, got $ACTUAL_SHA256)" >&2
    exit 1
  fi
  unzip -q "$TEMP_DIR/apache-maven.zip" -d "$TEMP_DIR"
  mv "$TEMP_DIR/apache-maven-$MAVEN_VERSION" "$MAVEN_HOME"
fi
exec "$MAVEN_BINARY" "$@"
