#!/usr/bin/env bash
# The CI test of the gateway image (kickoff 08, Part B; kickoff 10). It needs the image built as $IMAGE (default gateway:ci)
# and runs it like an operator would: read-only, as its own user, with a small tmpfs, the shop fixture mounted. It runs it
# twice. First with mock environments only and no folder mounted for the operator's libraries: an operation and the health
# paths, the bundle replaced and the new version served, SIGTERM and the exit code. Then with a stub of JTOpen's entry class
# mounted at /opt/slay400/ext, to see the gateway find it and name its version. Nothing is pushed anywhere. It prints the
# figures the pull request reports: the size of the image and how long the stop took.
set -euo pipefail

IMAGE=${IMAGE:-gateway:ci}
NAME=gateway-ci-$$
WORK=$(mktemp -d)
API=127.0.0.1:18080
MANAGEMENT=127.0.0.1:19090

cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "FAILED: $*" >&2
  docker logs "$NAME" 2>&1 | tail -n 50 >&2 || true
  exit 1
}

wait_ready() {
  for _ in $(seq 1 60); do
    if curl -fsS "http://$MANAGEMENT/health/ready" >/dev/null 2>&1; then return 0; fi
    sleep 0.5
  done
  fail "not ready on the management listener"
}

echo "== Two versions of the shop bundle"
./mvnw -B -q -f fixtures/shop/pom.xml package -Dpcml.attach=false -Dpcml.bundleFile="$WORK/shop-1.0.0.rpgapi"
./mvnw -B -q -f fixtures/shop/pom.xml package -Dpcml.attach=false -Dpcml.bundleVersion=1.1.0 -Dpcml.bundleFile="$WORK/shop-1.1.0.rpgapi"
mkdir -p "$WORK/etc" "$WORK/bundles"
cp "$WORK/shop-1.0.0.rpgapi" "$WORK/bundles/shop.rpgapi"
cat > "$WORK/etc/gateway.yaml" <<'YAML'
server: { address: 0.0.0.0, port: 8080 }
management: { address: 0.0.0.0, port: 9090 }
reload: { intervalSeconds: 1 }
tenants:
  acme:
    bundles: [/var/lib/slay400/bundles]
    environments:
      test: { code: T0001, backend: mock }
YAML

echo "== A stub of JTOpen's entry class, for the second run"
mkdir -p "$WORK/stub/src/com/ibm/as400/access" "$WORK/stub/classes/META-INF/maven/net.sf.jt400/jt400" "$WORK/ext"
printf 'package com.ibm.as400.access;\n\n/** A stub of the entry class of JTOpen, for the test of the image only. */\npublic class AS400 {\n}\n' \
  > "$WORK/stub/src/com/ibm/as400/access/AS400.java"
javac -d "$WORK/stub/classes" "$WORK/stub/src/com/ibm/as400/access/AS400.java"
printf 'groupId=net.sf.jt400\nartifactId=jt400\nversion=0.0.0-stub\n' > "$WORK/stub/classes/META-INF/maven/net.sf.jt400/jt400/pom.properties"
jar --create --file "$WORK/ext/jtopen-stub.jar" -C "$WORK/stub/classes" .
# The user of the image (10001) reads the mounted files.
chmod -R a+rX "$WORK"

echo "== The image"
SIZE=$(docker image inspect -f '{{.Size}}' "$IMAGE")
echo "Image size: $SIZE bytes ($((SIZE / 1024 / 1024)) MiB)"
[ "$(docker image inspect -f '{{.Config.User}}' "$IMAGE")" = "10001:10001" ] || fail "the image does not run as 10001:10001"
docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}} {{index .Config.Labels "org.opencontainers.image.source"}}' "$IMAGE"
docker image inspect -f 'Entry point {{json .Config.Entrypoint}}, command {{json .Config.Cmd}}' "$IMAGE"
# The description of the image is the notice of decision 149, word for word.
NOTICE='This distribution does not contain JT400 (JTOpen, the IBM Toolbox for Java). The `ibmi` backend needs it at run time. Operators obtain it themselves, under its own licence, the IBM Public License 1.0; the documentation explains how to download and add it.'
[ "$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.description"}}' "$IMAGE")" = "$NOTICE" ] || fail "the description of the image is not the notice of decision 149"
for file in LICENSE THIRD-PARTY-NOTICES.md bom.json VERSION bin/slay400-gateway lib/gateway.jar lib/ext/README; do
  docker run --rm --read-only --entrypoint /bin/sh "$IMAGE" -c "test -s /opt/slay400/$file" || fail "the image has no /opt/slay400/$file"
done
docker run --rm --read-only "$IMAGE" --version | grep -q '^slay400-gateway ' || fail "no version from the launcher"

echo "== Without a configuration it stops with the diagnostic of a missing file"
set +e
OUT=$(docker run --rm --read-only --tmpfs /tmp:rw,size=16m "$IMAGE" 2>&1)
CODE=$?
set -e
echo "$OUT" | tail -n 3
[ "$CODE" -eq 1 ] || fail "exit code $CODE without a configuration, not 1"
echo "$OUT" | grep -q "GWY001" || fail "no GWY001 without a configuration"

echo "== check, without listening"
docker run --rm --read-only --tmpfs /tmp:rw,size=64m -v "$WORK/etc:/etc/slay400:ro" -v "$WORK/bundles:/var/lib/slay400/bundles:ro" \
  "$IMAGE" check --config /etc/slay400/gateway.yaml | tee "$WORK/check.out"
grep -q "can be served" "$WORK/check.out" || fail "check did not pass"

echo "== Run 1: mock only, no folder for the operator's libraries"
docker run -d --name "$NAME" --read-only --tmpfs /tmp:rw,size=64m \
  -v "$WORK/etc:/etc/slay400:ro" -v "$WORK/bundles:/var/lib/slay400/bundles:ro" \
  -p "$API:8080" -p "$MANAGEMENT:9090" "$IMAGE" >/dev/null
wait_ready
docker logs "$NAME" 2>&1 | grep '"event":"started"'
docker logs "$NAME" 2>&1 | grep -q '"ext":"/opt/slay400/ext","jt400":"none"' || fail "the start line does not say that there is no JTOpen"

BODY=$(curl -fsS "http://$API/acme/test/orders/1001/status")
echo "$BODY"
echo "$BODY" | grep -q '"status":"S"' || fail "unexpected answer: $BODY"
[ "$(curl -s -o /dev/null -w '%{http_code}' "http://$API/health/live")" = "200" ] || fail "no health on the API listener"
[ "$(curl -s -o /dev/null -w '%{http_code}' "http://$MANAGEMENT/acme/test/orders/1001/status")" = "404" ] || fail "an operation on the management listener"
docker logs "$NAME" 2>&1 | grep -q '"bundleVersion":"1.0.0"' || fail "the first answer was not served by 1.0.0"

echo "== The bundle replaced, the new version served"
cp "$WORK/shop-1.1.0.rpgapi" "$WORK/bundles/shop.rpgapi.part"
mv "$WORK/bundles/shop.rpgapi.part" "$WORK/bundles/shop.rpgapi"
SERVED=no
for _ in $(seq 1 60); do
  curl -fsS -o /dev/null "http://$API/acme/test/orders/1001/status" || fail "a request failed during the reload"
  if docker logs "$NAME" 2>&1 | grep -q '"bundleVersion":"1.1.0"'; then SERVED=yes; break; fi
  sleep 0.5
done
[ "$SERVED" = yes ] || fail "version 1.1.0 is not served"
docker logs "$NAME" 2>&1 | grep '"event":"reloaded"'

echo "== SIGTERM"
STARTED=$(date +%s%N)
docker stop --time 10 "$NAME" >/dev/null
STOPPED=$(date +%s%N)
CODE=$(docker inspect -f '{{.State.ExitCode}}' "$NAME")
echo "Stopped in $(( (STOPPED - STARTED) / 1000000 )) ms, exit code $CODE"
docker logs "$NAME" 2>&1 | grep -q '"event":"stopped"' || fail "the gateway did not report that it stopped"
# A JVM that ends on SIGTERM through its shutdown hooks exits with 128 + 15.
[ "$CODE" -eq 143 ] || fail "exit code $CODE after SIGTERM, not 143"
docker rm "$NAME" >/dev/null

echo "== Run 2: a stub of JTOpen mounted at /opt/slay400/ext"
docker run -d --name "$NAME" --read-only --tmpfs /tmp:rw,size=64m \
  -v "$WORK/etc:/etc/slay400:ro" -v "$WORK/bundles:/var/lib/slay400/bundles:ro" -v "$WORK/ext:/opt/slay400/ext:ro" \
  -p "$API:8080" -p "$MANAGEMENT:9090" "$IMAGE" >/dev/null
wait_ready
docker logs "$NAME" 2>&1 | grep '"event":"started"'
docker logs "$NAME" 2>&1 | grep -q '"jt400":"0.0.0-stub"' || fail "the start line does not name the JTOpen found"
docker logs "$NAME" 2>&1 | grep -q 'GWY028' || fail "no warning for a version that has not been verified"
curl -fsS -o /dev/null "http://$API/acme/test/orders/1001/status" || fail "the mock does not answer with JTOpen mounted"
docker stop --time 10 "$NAME" >/dev/null
[ "$(docker inspect -f '{{.State.ExitCode}}' "$NAME")" -eq 143 ] || fail "the second run did not stop with 143"
echo "Container test passed"
