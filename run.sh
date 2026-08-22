#!/usr/bin/env bash
# Starts MediQueue. Run ./build.sh first.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f out/ng/unilag/mediqueue/MediQueueApplication.class ]; then
  echo "  Not built yet. Run ./build.sh first."
  exit 1
fi

# Windows uses ';' between classpath entries, everything else uses ':'.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *)                    SEP=':' ;;
esac

# -Ddemo.seed=true belongs here rather than in config.properties: that file ships inside
# the packaged jar, so a value of true there would follow the build into production.
exec java -Ddemo.seed=true \
     -cp "out${SEP}lib/postgresql-42.7.4.jar" ng.unilag.mediqueue.MediQueueApplication "$@"
