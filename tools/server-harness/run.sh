#!/usr/bin/env bash
# Exercises the local HTTP server on a plain JVM: no device, no Android SDK.
# Usage: tools/server-harness/run.sh   (starts the harness on :18080 and runs the checks)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
build="$here/build"
mkdir -p "$build"
jar="$build/nanohttpd.jar"
[ -f "$jar" ] || curl -sSL -o "$jar" https://repo1.maven.org/maven2/org/nanohttpd/nanohttpd/2.3.1/nanohttpd-2.3.1.jar

javac -cp "$jar" -d "$build/classes" \
    "$here"/Harness.java "$here"/FakeBridge.java \
    "$repo"/app/src/main/java/ru/ytkab0bp/slicebeam/server/PlateBridge.java \
    "$repo"/app/src/main/java/ru/ytkab0bp/slicebeam/server/SliceJob.java \
    "$repo"/app/src/main/java/ru/ytkab0bp/slicebeam/server/LocalHttpServer.java

java -cp "$jar:$build/classes" Harness "$repo/app/src/main/assets/web" "$build/uploads" 18081 > "$build/server.log" 2>&1 &
server_pid=$!
trap 'kill $server_pid 2>/dev/null || true' EXIT
for _ in $(seq 1 40); do
    curl -s -o /dev/null "http://127.0.0.1:18081/?t=test-token" && break
    sleep 0.25
done

base=http://127.0.0.1:18081
h='X-Orca-Token: test-token'
fail=0
check() { # name expected actual
    if [ "$2" = "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1: esperado $2, obtenido $3"; fail=1; fi
}

check "sin token -> 401" 401 "$(curl -s -o /dev/null -w '%{http_code}' "$base/api/status")"
check "con token -> 200" 200 "$(curl -s -o /dev/null -w '%{http_code}' -H "$h" "$base/api/status")"
check "index.html servido" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$base/?t=test-token")"

head -c 3000000 /dev/urandom > "$build/pieza.stl"
check "subida 3 MB" 200 "$(curl -s -o "$build/up.json" -w '%{http_code}' -T "$build/pieza.stl" -H "$h" \
    -H 'Content-Type: application/octet-stream' "$base/api/files/pieza.stl")"
check "bytes en disco" 3000000 "$(stat -c%s "$build/uploads/pieza.stl")"

check "laminar -> 202" 202 "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "$h" "$base/api/slice")"
check "doble laminado -> 409" 409 "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "$h" "$base/api/slice")"
sleep 2
check "job done" done "$(curl -s -H "$h" "$base/api/job" | python3 -c 'import sys,json;print(json.load(sys.stdin)["state"])')"
check "gcode descargable" 200 "$(curl -s -o "$build/out.gcode" -w '%{http_code}' -H "$h" "$base/api/gcode")"
check "paquete 3mf" 200 "$(curl -s -o "$build/out.3mf" -w '%{http_code}' -H "$h" "$base/api/package")"
check "borrar pieza" 200 "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE -H "$h" "$base/api/files/0")"
check "sin content-length -> 411" 411 "$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "$h" -H 'Transfer-Encoding: chunked' \
    --data-binary @$build/pieza.stl "$base/api/files/chunk.stl")"

[ $fail -eq 0 ] && echo "TODO OK" || { echo "FALLOS"; exit 1; }
