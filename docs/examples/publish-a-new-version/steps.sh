#!/bin/sh
# Publish a new version: a second version of the shop bundle replaces the first while the gateway serves requests.
set -eu

# region: install-slay400
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400-core/main/install.sh | sh
export PATH="$HOME/.slay400/bin:$PATH"
# endregion

# region: install-gateway
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400/main/install.sh | sh
export PATH="$HOME/.slay400-gateway/bin:$PATH"
# endregion

# region: bundles
slay400 init
slay400 bundle --name shop --version 1.0.0 --out build/shop-1.0.0.rpgapi
slay400 bundle --name shop --version 1.1.0 --out build/shop-1.1.0.rpgapi
# endregion

# region: config
mkdir -p bundles
cp build/shop-1.0.0.rpgapi bundles/shop.rpgapi
cat > config.yaml <<'YAML'
reload:
  intervalSeconds: 1
tenants:
  acme:
    bundles: [./bundles]
    environments:
      test: { code: T0001, backend: mock }
YAML
# endregion

# region: start
slay400-gateway run --config config.yaml > gateway.log 2>&1 &
until curl -fs -o /dev/null http://127.0.0.1:8080/health/ready; do sleep 1; done
# endregion

# region: before
curl -s http://127.0.0.1:8080/acme/test/orders/1001/status
grep -o '"bundle":"shop","bundleVersion":"[^"]*"' gateway.log
# endregion

# region: publish
cp build/shop-1.1.0.rpgapi bundles/shop.rpgapi.part
mv bundles/shop.rpgapi.part bundles/shop.rpgapi
until grep -q '"event":"reloaded"' gateway.log; do sleep 1; done
grep -o '"event":"reloaded","bundles":\[[^]]*\],"operations":{[^}]*}' gateway.log
# endregion

# region: after
curl -s http://127.0.0.1:8080/acme/test/orders/1001/status
grep -o '"bundle":"shop","bundleVersion":"[^"]*"' gateway.log | tail -n 1
# endregion

# region: stop
kill $!
# endregion
