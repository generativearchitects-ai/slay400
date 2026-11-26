#!/bin/sh
# Tenants and environments: one tenant, two environments with their own code and backend, one bundle.
set -eu

# region: install-slay400
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400-core/main/install.sh | sh
export PATH="$HOME/.slay400/bin:$PATH"
# endregion

# region: install-gateway
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400/main/install.sh | sh
export PATH="$HOME/.slay400-gateway/bin:$PATH"
# endregion

# region: bundle
slay400 init
slay400 bundle --name shop --version 1.0.0 --out build/shop-1.0.0.rpgapi
# endregion

# region: config
cat > config.yaml <<'YAML'
tenants:
  acme:
    bundles: [./build/shop-1.0.0.rpgapi]
    environments:
      test: { code: T0001, backend: mock }
      staging: { code: S0001, backend: mock }
YAML
slay400-gateway check --config config.yaml
# endregion

# region: start
slay400-gateway run --config config.yaml > gateway.log 2>&1 &
until curl -fs -o /dev/null http://127.0.0.1:8080/health/ready; do sleep 1; done
# endregion

# region: call-both
curl -s http://127.0.0.1:8080/acme/test/orders/1001/status
echo
curl -s http://127.0.0.1:8080/acme/staging/orders/1001/status
echo
grep -o '"tenant":"[^"]*","environment":"[^"]*"' gateway.log
# endregion

# region: unknown
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/acme/prod/orders/1001/status
# endregion

# region: stop
kill $!
# endregion

# region: two-tenants
cat > two-tenants.yaml <<'YAML'
tenants:
  acme:
    bundles: [./build/shop-1.0.0.rpgapi]
    environments:
      test: { code: T0001, backend: mock }
  globex:
    bundles: [./build/shop-1.0.0.rpgapi]
    environments:
      test: { code: T0001, backend: mock }
YAML
slay400-gateway check --config two-tenants.yaml 2>&1 || echo "exit $?"
# endregion
