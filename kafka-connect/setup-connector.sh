#!/bin/bash
# Run this ONCE before 'docker compose build' to prepare the Iceberg Kafka Connect plugin.
# Requires: Java 17+, git

set -e

PLUGINS_DIR="$(cd "$(dirname "$0")" && pwd)/plugins"
ICEBERG_VERSION="1.7.1"

if ls "$PLUGINS_DIR"/*.jar 2>/dev/null | grep -q iceberg; then
  echo "Iceberg connector JARs already present in $PLUGINS_DIR"
  exit 0
fi

mkdir -p "$PLUGINS_DIR"

echo "Building Apache Iceberg Kafka Connect runtime from source (version $ICEBERG_VERSION)..."
echo "This takes ~3-5 minutes on first run."

TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

git clone --depth 1 --branch apache-iceberg-${ICEBERG_VERSION} \
  https://github.com/apache/iceberg.git "$TMPDIR/iceberg"

cd "$TMPDIR/iceberg"

# Discover the correct task path
echo "Discovering Gradle project structure..."
RUNTIME_PROJECT=$(./gradlew projects --no-daemon -q 2>/dev/null | grep -i "kafka-connect-runtime" | head -1 | sed "s/.*--- Project '//;s/'.*//")

if [ -z "$RUNTIME_PROJECT" ]; then
  # Try building the full kafka-connect subproject
  echo "Building kafka-connect module..."
  ./gradlew -x test -x integrationTest \
    :kafka-connect:build \
    --no-daemon -q
else
  echo "Found runtime project: $RUNTIME_PROJECT"
  ./gradlew -x test -x integrationTest \
    "${RUNTIME_PROJECT}:build" \
    --no-daemon -q
fi

# Find the distribution zip (prefer non-hive variant)
ZIP=$(find . -path "*/kafka-connect*/distributions/*.zip" | grep -v hive | head -1)
if [ -z "$ZIP" ]; then
  ZIP=$(find . -path "*/kafka-connect*/distributions/*.zip" | head -1)
fi

if [ -z "$ZIP" ]; then
  echo "ERROR: Could not find distribution zip. Trying to find any built JARs..."
  find . -path "*/kafka-connect*/libs/*.jar" -not -name "*javadoc*" -not -name "*sources*" \
    -exec cp {} "$PLUGINS_DIR/" \;
else
  echo "Extracting JARs from $ZIP to $PLUGINS_DIR..."
  unzip -j "$ZIP" "*/lib/*.jar" -d "$PLUGINS_DIR/"
fi

JAR_COUNT=$(ls "$PLUGINS_DIR"/*.jar 2>/dev/null | wc -l)
echo ""
echo "Done. $JAR_COUNT JARs extracted to $PLUGINS_DIR"
echo "Now run: docker compose build kafka-connect && docker compose up -d"
