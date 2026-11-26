#!/bin/sh
# Serve with a mock: the quickstart's bundle, served by the gateway with its mock backend and called with curl. No IBM i.
set -eu

# region: install-slay400
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400-core/main/install.sh | sh
export PATH="$HOME/.slay400/bin:$PATH"
# endregion

# region: init
slay400 init
# endregion

# region: bundle
slay400 bundle --name shop --version 1.0.0 --out build/shop-1.0.0.rpgapi
# endregion

# region: install-gateway
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400/main/install.sh | sh
export PATH="$HOME/.slay400-gateway/bin:$PATH"
# endregion

# region: check-gateway
slay400-gateway --version
# endregion

# region: config
cat > config.yaml <<'YAML'
tenants:
  acme:
    bundles: [./build/shop-1.0.0.rpgapi]
    environments:
      test: { code: T0001, backend: mock }
YAML
# endregion

# region: check
slay400-gateway check --config config.yaml
# endregion

# region: start
slay400-gateway run --config config.yaml > gateway.log 2>&1 &
until curl -fs -o /dev/null http://127.0.0.1:8080/health/ready; do sleep 1; done
# endregion

# region: call-order
curl -s http://127.0.0.1:8080/acme/test/orders/1001/status
# endregion

# region: call-item
curl -s -X POST 'http://127.0.0.1:8080/acme/test/items/ABC12345/quantity?warehouse=W01' \
  -H 'X-Channel: WEB' -H 'Content-Type: application/json' -d '{"quantity": 5}'
# endregion

# region: access
grep -o '"tenant".*"status":[0-9]*' gateway.log
# endregion

# region: stop
kill $!
# endregion
