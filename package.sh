#!/usr/bin/env bash
# Packages MediQueue as ONE runnable file: dist/mediqueue.jar
#
# Deployment then needs nothing but a JRE:
#     java -jar mediqueue.jar
#
# The PostgreSQL driver is unpacked into the jar alongside the application classes rather
# than sitting beside it, because a Class-Path manifest entry pointing at lib/ breaks the
# moment someone copies the jar on its own -- which is exactly what people do.
set -euo pipefail
cd "$(dirname "$0")"

DRIVER=lib/postgresql-42.7.4.jar
STAGE=build/jar
OUT=dist/mediqueue.jar

if [ ! -f "$DRIVER" ]; then
  echo "  Missing $DRIVER -- run build.sh first for the download link." >&2
  exit 1
fi

# Locate the 'jar' tool. It is not always beside javac: some installers put a stub
# directory on PATH holding only java/javac/javaw/jshell, so javac resolves while jar
# does not. Prefer JAVA_HOME, then PATH.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jar" ]; then
  JAR="$JAVA_HOME/bin/jar"
elif command -v jar >/dev/null 2>&1; then
  JAR=jar
else
  echo "  Could not find the 'jar' tool. It ships with the JDK, not the JRE." >&2
  echo "  Set JAVA_HOME to your JDK folder and try again." >&2
  exit 1
fi

echo "[package] Compiling ..."
rm -rf "$STAGE" && mkdir -p "$STAGE" dist
find src/main/java -name '*.java' > sources.txt
# --release 17 so the jar runs on any JRE 17 or newer, not just the JDK that built it.
javac --release 17 -d "$STAGE" -cp "$DRIVER" @sources.txt

echo "[package] Adding resources ..."
cp -r src/main/resources/. "$STAGE"/

echo "[package] Unpacking the JDBC driver ..."
( cd "$STAGE" && "$JAR" xf "$OLDPWD/$DRIVER" )
# The driver ships its own manifest and signature files. Left in place, the signatures no
# longer match the repacked contents and the JVM refuses to load the classes.
rm -rf "$STAGE/META-INF/MANIFEST.MF" "$STAGE"/META-INF/*.SF \
       "$STAGE"/META-INF/*.DSA "$STAGE"/META-INF/*.RSA 2>/dev/null || true

echo "[package] Building $OUT ..."
"$JAR" --create --file "$OUT" --main-class ng.unilag.mediqueue.MediQueueApplication -C "$STAGE" .

echo "[package] Done -- $(du -h "$OUT" | cut -f1) at $OUT"
echo "          Run it with: java -jar $OUT"
