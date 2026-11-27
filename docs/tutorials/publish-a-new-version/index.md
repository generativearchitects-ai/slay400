---
title: Publish a new version
description: Replace the bundle a running gateway serves, without a restart and without failing a request.
section: tutorials
slug: publish-a-new-version
order: 20
appliesTo: "0.1"
time: 10 min
---

# Publish a new version

At the end of this page you will have replaced the bundle that a running gateway serves with a new version, without a restart, and seen in the log when the gateway switched and which version served each request. It uses the mock, so you need no IBM i.

Before you start, you need what [Serve with a mock](/docs/get-started/serve-with-a-mock/) needs, in an empty folder with the `apis/` files of the [quickstart](/docs/get-started/quickstart/).

## Install the tools

This installs `slay400`, the generator, and the gateway, as [Serve with a mock](/docs/get-started/serve-with-a-mock/) explains:

<!-- snippet: examples/publish-a-new-version/steps.sh#install-slay400 -->
```sh
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400-core/main/install.sh | sh
export PATH="$HOME/.slay400/bin:$PATH"
```

<!-- snippet: examples/publish-a-new-version/steps.sh#install-gateway -->
```sh
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400/main/install.sh | sh
export PATH="$HOME/.slay400-gateway/bin:$PATH"
```

Each prints where it installed the tool and the line that puts it on your `PATH`.

## Build two versions

Build the same APIs twice, as version 1.0.0 and 1.1.0 of the bundle `shop`:

<!-- snippet: examples/publish-a-new-version/steps.sh#bundles -->
```sh
slay400 init
slay400 bundle --name shop --version 1.0.0 --out build/shop-1.0.0.rpgapi
slay400 bundle --name shop --version 1.1.0 --out build/shop-1.1.0.rpgapi
```

## Serve a folder of bundles

The gateway serves every `*.rpgapi` file of a folder named in `bundles`. This copies version 1.0.0 into `bundles/` and writes a configuration that looks at the folder every second (`reload.intervalSeconds`; the default is 10):

<!-- snippet: examples/publish-a-new-version/steps.sh#config -->
```sh
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
```

Start the gateway:

<!-- snippet: examples/publish-a-new-version/steps.sh#start -->
```sh
slay400-gateway run --config config.yaml > gateway.log 2>&1 &
until curl -fs -o /dev/null http://127.0.0.1:8080/health/ready; do sleep 1; done
```

Call an operation, and look at which version served it:

<!-- snippet: examples/publish-a-new-version/steps.sh#before -->
```sh
curl -s http://127.0.0.1:8080/acme/test/orders/1001/status
grep -o '"bundle":"shop","bundleVersion":"[^"]*"' gateway.log
```

It prints the answer, then the bundle and version of the access line:

<!-- snippet: examples/publish-a-new-version/expected/before.out -->
```text
{"status":"S","deliveryDate":"2026-11-20"}"bundle":"shop","bundleVersion":"1.0.0"
```

## Publish version 1.1.0

Copy the new file next to the old one under another name, then rename it into place: the rename is a single step, so the gateway never sees half a file. It loads a changed folder only once it has stayed the same for two looks in a row. The last command waits for the line that the gateway writes when it switches:

<!-- snippet: examples/publish-a-new-version/steps.sh#publish -->
```sh
cp build/shop-1.1.0.rpgapi bundles/shop.rpgapi.part
mv bundles/shop.rpgapi.part bundles/shop.rpgapi
until grep -q '"event":"reloaded"' gateway.log; do sleep 1; done
grep -o '"event":"reloaded","bundles":\[[^]]*\],"operations":{[^}]*}' gateway.log
```

It prints the switch: the bundles now served, and the operations added, removed and changed:

<!-- snippet: examples/publish-a-new-version/expected/publish.out -->
```text
"event":"reloaded","bundles":[{"tenant":"acme","name":"shop","version":"1.1.0"}],"operations":{"added":0,"removed":0,"changed":0}
```

The line also has `durationMs`, how long the load took. Requests that arrived before the switch finish on version 1.0.0; none fails or waits.

Call again:

<!-- snippet: examples/publish-a-new-version/steps.sh#after -->
```sh
curl -s http://127.0.0.1:8080/acme/test/orders/1001/status
grep -o '"bundle":"shop","bundleVersion":"[^"]*"' gateway.log | tail -n 1
```

The answer is the same, and version 1.1.0 served it:

<!-- snippet: examples/publish-a-new-version/expected/after.out -->
```text
{"status":"S","deliveryDate":"2026-11-20"}"bundle":"shop","bundleVersion":"1.1.0"
```

If the new set cannot be loaded, the gateway keeps the old one and writes one warning with the diagnostics; it tries again only when the files change again.

## Stop the gateway

<!-- snippet: examples/publish-a-new-version/steps.sh#stop -->
```sh
kill $!
```

To clean up, delete the folder.
