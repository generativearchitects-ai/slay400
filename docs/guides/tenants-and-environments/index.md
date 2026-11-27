---
title: Tenants and environments
description: How one gateway serves the same APIs to several environments, each with its own code and backend.
section: guides
slug: tenants-and-environments
order: 10
appliesTo: "0.1"
---

# Tenants and environments

This page shows how to serve the same bundle in two environments of one tenant, and what the gateway says about a second tenant. A tenant is the owner of a set of bundles; an environment is one place its programs run, such as `test` or `prod`, with its own code, backend and credentials. Both are part of every address, `/<tenant>/<environment>/<route>`, and of every access line.

## Install the tools and build the bundle

This installs `slay400`, the generator, and the gateway, as [Serve with a mock](/docs/get-started/serve-with-a-mock/) explains:

<!-- snippet: examples/tenants-and-environments/steps.sh#install-slay400 -->
```sh
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400-core/main/install.sh | sh
export PATH="$HOME/.slay400/bin:$PATH"
```

<!-- snippet: examples/tenants-and-environments/steps.sh#install-gateway -->
```sh
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400/main/install.sh | sh
export PATH="$HOME/.slay400-gateway/bin:$PATH"
```

Each prints where it installed the tool and the line that puts it on your `PATH`.

<!-- snippet: examples/tenants-and-environments/steps.sh#bundle -->
```sh
slay400 init
slay400 bundle --name shop --version 1.0.0 --out build/shop-1.0.0.rpgapi
```

## Two environments

Each environment has a `code`, which an overlay can pass to the program (`${environment.code}`), and a `backend`. Here both are mocks:

<!-- snippet: examples/tenants-and-environments/steps.sh#config -->
```sh
cat > config.yaml <<'YAML'
tenants:
  acme:
    bundles: [./build/shop-1.0.0.rpgapi]
    environments:
      test: { code: T0001, backend: mock }
      staging: { code: S0001, backend: mock }
YAML
slay400-gateway check --config config.yaml
```

It prints:

<!-- snippet: examples/tenants-and-environments/expected/config.out -->
```text
slay400-gateway check: config.yaml can be served, 2 operations, 0 warnings
```

Start the gateway, then call the same operation in both environments:

<!-- snippet: examples/tenants-and-environments/steps.sh#start -->
```sh
slay400-gateway run --config config.yaml > gateway.log 2>&1 &
until curl -fs -o /dev/null http://127.0.0.1:8080/health/ready; do sleep 1; done
```

<!-- snippet: examples/tenants-and-environments/steps.sh#call-both -->
```sh
curl -s http://127.0.0.1:8080/acme/test/orders/1001/status
echo
curl -s http://127.0.0.1:8080/acme/staging/orders/1001/status
echo
grep -o '"tenant":"[^"]*","environment":"[^"]*"' gateway.log
```

The answers are the same mock; the access lines name each environment:

<!-- snippet: examples/tenants-and-environments/expected/call-both.out -->
```text
{"status":"S","deliveryDate":"2026-11-20"}
{"status":"S","deliveryDate":"2026-11-20"}
"tenant":"acme","environment":"test"
"tenant":"acme","environment":"staging"
```

An environment that the configuration does not name is not found:

<!-- snippet: examples/tenants-and-environments/steps.sh#unknown -->
```sh
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/acme/prod/orders/1001/status
```

It prints:

<!-- snippet: examples/tenants-and-environments/expected/unknown.out -->
```text
404
```

<!-- snippet: examples/tenants-and-environments/steps.sh#stop -->
```sh
kill $!
```

## One tenant in the Community Edition

The Community Edition serves one tenant. A configuration with two does not start:

<!-- snippet: examples/tenants-and-environments/steps.sh#two-tenants -->
```sh
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
```

It prints:

<!-- snippet: examples/tenants-and-environments/expected/two-tenants.out -->
```text
two-tenants.yaml:6:3: error GWY008: The Community Edition serves 1 tenant; this configuration has 2
1 error, 0 warnings, 0 notes
slay400-gateway check: two-tenants.yaml cannot be served
exit 1
```

The names of tenants and environments have lower-case letters, digits and `-`; `health` is reserved. The [configuration reference](/docs/reference/configuration/) has every key of an environment, and [Credentials](/docs/guides/credentials/) how an `ibmi` environment signs on.
