#!/usr/bin/env bash
# Compiles MediQueue with the JDK alone -- no Maven, no Gradle.
# macOS/Linux/Git Bash counterpart of build.bat.
set -euo pipefail
cd "$(dirname "$0")"

DRIVER=lib/postgresql-42.7.4.jar

if [ ! -f "$DRIVER" ]; then
  echo
  echo "  Missing $DRIVER"
  echo "  Download it from:"
  echo "  https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar"
  echo
  exit 1
fi

echo "[build] Collecting sources ..."
find src/main/java -name '*.java' > sources.txt

echo "[build] Compiling ..."
mkdir -p out
javac -d out -cp "$DRIVER" @sources.txt

echo "[build] Copying resources ..."
cp -r src/main/resources/. out/

echo "[build] Done. Run ./run.sh to start MediQueue."
