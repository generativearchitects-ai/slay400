#!/usr/bin/env bash
# The CI test of the gateway image (kickoff 08, Part B). It needs the image built as $IMAGE (default gateway:ci) and runs it
# like an operator would: read-only, as its own user, with a small tmpfs, the shop fixture mounted. It calls an operation and
# the health paths, replaces the bundle and sees the new version served, sends SIGTERM and checks the exit code. Nothing is
# pushed anywhere. It prints the figures the pull request reports: the size of the image and how long the stop took.
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
# The user of the image (10001) reads the mounted files.
chmod -R a+rX "$WORK"

echo "== The image"
SIZE=$(docker image inspect -f '{{.Size}}' "$IMAGE")
echo "Image size: $SIZE bytes ($((SIZE / 1024 / 1024)) MiB)"
[ "$(docker image inspect -f '{{.Config.User}}' "$IMAGE")" = "10001:10001" ] || fail "the image does not run as 10001:10001"
docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}} {{index .Config.Labels "org.opencontainers.image.source"}}' "$IMAGE"
for file in LICENSE THIRD-PARTY-NOTICES.md bom.json gateway.jar; do
  docker run --rm --read-only --entrypoint /bin/sh "$IMAGE" -c "test -s /opt/gateway/$file" || fail "the image has no /opt/gateway/$file"
done

echo "== Without a configuration it stops with the diagnostic of a missing file"
set +e
OUT=$(docker run --rm --read-only --tmpfs /tmp:rw,size=16m "$IMAGE" 2>&1)
CODE=$?
set -e
echo "$OUT" | tail -n 3
[ "$CODE" -eq 1 ] || fail "exit code $CODE without a configuration, not 1"
echo "$OUT" | grep -q "GWY001" || fail "no GWY001 without a configuration"

echo "== Start"
docker run -d --name "$NAME" --read-only --tmpfs /tmp:rw,size=64m \
  -v "$WORK/etc:/etc/slay400:ro" -v "$WORK/bundles:/var/lib/slay400/bundles:ro" \
  -p "$API:8080" -p "$MANAGEMENT:9090" "$IMAGE" >/dev/null
for _ in $(seq 1 60); do
  if curl -fsS "http://$MANAGEMENT/health/ready" >/dev/null 2>&1; then break; fi
  sleep 0.5
done
curl -fsS "http://$MANAGEMENT/health/ready" >/dev/null || fail "not ready on the management listener"

echo "== An operation and the health paths"
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
echo "Container test passed"
