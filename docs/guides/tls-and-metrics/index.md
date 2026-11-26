---
title: TLS and the management listener
description: Serve the APIs over HTTPS, and check the gateway's health on the management listener.
section: guides
slug: tls-and-metrics
order: 30
appliesTo: "0.1"
---

# TLS and the management listener

This page shows how to make the API listener speak HTTPS with your certificate, and how to check the gateway's health on its second listener, the management listener. It uses the mock, so you need no IBM i, and OpenSSL 3 to make a test certificate; macOS ships LibreSSL, whose key files the gateway cannot read, so install OpenSSL 3 there.

## Install the tools and build the bundle

This installs `slay400`, the generator, and the gateway, as [Serve with a mock](/docs/get-started/serve-with-a-mock/) explains:

<!-- snippet: examples/tls-and-metrics/steps.sh#install-slay400 -->
```sh
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400-core/main/install.sh | sh
export PATH="$HOME/.slay400/bin:$PATH"
```

<!-- snippet: examples/tls-and-metrics/steps.sh#install-gateway -->
```sh
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400/main/install.sh | sh
export PATH="$HOME/.slay400-gateway/bin:$PATH"
```

Each prints where it installed the tool and the line that puts it on your `PATH`.

<!-- snippet: examples/tls-and-metrics/steps.sh#bundle -->
```sh
slay400 init
slay400 bundle --name shop --version 1.0.0 --out build/shop-1.0.0.rpgapi
```

## A certificate

The gateway reads a certificate and its private key from two PEM files; the key is PKCS#8 (`-----BEGIN PRIVATE KEY-----`). This makes a self-signed certificate for `localhost`, valid for a year:

<!-- snippet: examples/tls-and-metrics/steps.sh#certificate -->
```sh
mkdir -p tls
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 365 \
  -subj /CN=localhost -addext subjectAltName=DNS:localhost,IP:127.0.0.1 -keyout tls/tls.key -out tls/tls.crt 2>/dev/null
head -n 1 tls/tls.key
```

It prints the first line of the key:

<!-- snippet: examples/tls-and-metrics/expected/certificate.out -->
```text
-----BEGIN PRIVATE KEY-----
```

A key in another form, such as `-----BEGIN RSA PRIVATE KEY-----`, is converted with `openssl pkcs8 -topk8`. An encrypted key needs `keyPassword`, a placeholder like a [credential](/docs/guides/credentials/).

## HTTPS on the API listener

With `server.tls`, the API listener speaks HTTPS only, TLS 1.3 and 1.2:

<!-- snippet: examples/tls-and-metrics/steps.sh#config -->
```sh
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
```

It prints:

<!-- snippet: examples/tls-and-metrics/expected/config.out -->
```text
slay400-gateway check: config.yaml can be served, 2 operations, 0 warnings
```

A certificate that is expired, not yet valid, or not the key's stops the start (GWY022 to GWY024); one that expires within 30 days is a warning (GWY025).

Start the gateway. This waits until the API listener answers over HTTPS:

<!-- snippet: examples/tls-and-metrics/steps.sh#start -->
```sh
slay400-gateway run --config config.yaml > gateway.log 2>&1 &
until curl -fs --cacert tls/tls.crt -o /dev/null https://localhost:8443/health/ready; do sleep 1; done
```

Call an operation over HTTPS, trusting the test certificate:

<!-- snippet: examples/tls-and-metrics/steps.sh#call-https -->
```sh
curl -s --cacert tls/tls.crt https://localhost:8443/acme/test/orders/1001/status
```

It prints:

<!-- snippet: examples/tls-and-metrics/expected/call-https.out -->
```text
{"status":"S","deliveryDate":"2026-11-20"}
```

Plain HTTP on the same port gets no answer:

<!-- snippet: examples/tls-and-metrics/steps.sh#call-http -->
```sh
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8443/acme/test/orders/1001/status || true
```

It prints:

<!-- snippet: examples/tls-and-metrics/expected/call-http.out -->
```text
000
```

To renew the certificate, write the new files in place: the gateway looks at them every `reload.intervalSeconds` and uses a new pair for new connections once it has stayed the same for two looks.

## The management listener

The management listener speaks plain HTTP, on `127.0.0.1:9090` unless `management` says otherwise, and serves the health checks. Keep it on an address that only your monitoring reaches.

<!-- snippet: examples/tls-and-metrics/steps.sh#health -->
```sh
curl -s -w '\n' http://127.0.0.1:9090/health/live
curl -s -w '\n' http://127.0.0.1:9090/health/ready
```

It prints:

<!-- snippet: examples/tls-and-metrics/expected/health.out -->
```text
{"status":"ok"}
{"status":"ok"}
```

It never serves an operation:

<!-- snippet: examples/tls-and-metrics/steps.sh#no-operation -->
```sh
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9090/acme/test/orders/1001/status
```

It prints:

<!-- snippet: examples/tls-and-metrics/expected/no-operation.out -->
```text
404
```

`/health/live` answers once the gateway runs; `/health/ready` once it serves its bundles. Metrics on the management listener are planned for a later version.

<!-- snippet: examples/tls-and-metrics/steps.sh#stop -->
```sh
kill $!
```

To clean up, delete the folder.
