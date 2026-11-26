#!/bin/sh
# TLS and the management listener: the API listener with a certificate, the health checks on the management listener.
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

# region: certificate
mkdir -p tls
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 365 \
  -subj /CN=localhost -addext subjectAltName=DNS:localhost,IP:127.0.0.1 -keyout tls/tls.key -out tls/tls.crt 2>/dev/null
head -n 1 tls/tls.key
# endregion

# region: config
cat > config.yaml <<'YAML'
server:
  port: 8443
  tls:
    certificate: tls/tls.crt
    key: tls/tls.key
management:
  port: 9090
tenants:
  acme:
    bundles: [./build/shop-1.0.0.rpgapi]
    environments:
      test: { code: T0001, backend: mock }
YAML
slay400-gateway check --config config.yaml
# endregion

# region: start
slay400-gateway run --config config.yaml > gateway.log 2>&1 &
until curl -fs -o /dev/null http://127.0.0.1:9090/health/ready; do sleep 1; done
# endregion

# region: call-https
curl -s --cacert tls/tls.crt https://localhost:8443/acme/test/orders/1001/status
# endregion

# region: call-http
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8443/acme/test/orders/1001/status || true
# endregion

# region: health
curl -s -w '\n' http://127.0.0.1:9090/health/live
curl -s -w '\n' http://127.0.0.1:9090/health/ready
# endregion

# region: no-operation
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9090/acme/test/orders/1001/status
# endregion

# region: stop
kill $!
# endregion
