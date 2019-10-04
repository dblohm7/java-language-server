#!/bin/bash

set -e

# Needed once
if [ ! -e node_modules ]; then
    npm install
fi

# Build standalone java
if [ ! -e jdks/linux/jdk-13 ]; then
    ./scripts/download_linux_jdk.sh
fi
if [ ! -e dist/linux/bin/java ]; then
    ./scripts/link_linux.sh
fi

# Compile sources
if [ ! -e src/main/java/com/google/devtools/build/lib/analysis/AnalysisProtos.java ]; then
    ./scripts/gen_proto.sh
fi
./scripts/format.sh
mvn package -DskipTests

