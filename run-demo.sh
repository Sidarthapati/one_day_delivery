#!/usr/bin/env bash
# Run the demo app from the packaged JAR — immune to the "torn build" NoClassDefFoundError.
#
# spring-boot:run executes the exploded target/classes directory, so `mvn clean` (or a raced recompile)
# deletes class files out from under the running JVM → lazy-loaded classes like DemoDaController$1
# (an enum switch-map) vanish → NoClassDefFoundError surfaced as a bare 401. Running java -jar loads
# from an in-memory jar snapshot, so a later `mvn clean install` cannot tear the live process.
#
# Brings up BOTH the backend (:8080, from the jar) and the demo UI (:5173, Vite dev server).
#
# Usage:  ./run-demo.sh          # build backend if needed, then run backend + UI
#         ./run-demo.sh --build  # force a clean backend rebuild first
#         ./run-demo.sh --no-ui  # backend only
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
set -a; [ -f .env ] && source .env; set +a

# Stop anything already on :8080 / :5173 (never rebuild the backend while it's live).
for port in 8080 5173; do lsof -ti tcp:$port | xargs kill 2>/dev/null || true; done; sleep 2
for port in 8080 5173; do lsof -ti tcp:$port | xargs kill -9 2>/dev/null || true; done

JAR=$(ls app/target/*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1 || true)
if [ "${1:-}" = "--build" ] || [ -z "$JAR" ]; then
  "$JAVA_HOME/bin/java" -version
  mvn clean install -pl app -am -DskipTests
  JAR=$(ls app/target/*.jar | grep -v -- '-sources\|-javadoc' | head -1)
fi

# Vite dev server for the demo UI (unless --no-ui). It serves source directly (HMR) — no build to tear —
# and proxies /api → :8080 and /osrm → OSRM. Started in the background; killed when this script exits.
VITE_PID=""
if [ "${1:-}" != "--no-ui" ]; then
  ( cd demo-ui && nohup npx vite --host > /tmp/oneday-vite.log 2>&1 & echo $! > /tmp/oneday-vite.pid )
  VITE_PID=$(cat /tmp/oneday-vite.pid)
  echo "▶ demo UI (Vite) starting → http://localhost:5173/  (log: /tmp/oneday-vite.log)"
fi
trap '[ -n "$VITE_PID" ] && kill "$VITE_PID" 2>/dev/null || true' EXIT

echo "▶ backend running $JAR (from jar snapshot — clean-safe) → http://localhost:8080/"
"$JAVA_HOME/bin/java" -jar "$JAR"
