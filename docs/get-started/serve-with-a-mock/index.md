---
title: Serve with a mock
description: Serve the quickstart's bundle with the gateway and call it with curl, without an IBM i.
section: get-started
slug: serve-with-a-mock
order: 20
appliesTo: "0.1"
time: 10 min
---

# Serve with a mock

At the end of this page you will have served the `shop` bundle of the quickstart with the gateway, called its two operations with `curl`, and read what the gateway logged for each call. The gateway answers from the mock definitions in the bundle, so you need no IBM i (AS/400), no program and nothing else.

Before you start, you need:

- A POSIX shell (Linux or macOS) and `curl`.
- Java 21 or later. Check with `java -version`.
- The bundle `build/shop-1.0.0.rpgapi` of the [quickstart](/docs/get-started/quickstart/). If you finished it, work in its folder and go on to "Install the gateway". If not, make an empty folder with the `apis/` files that the quickstart shows, and build the bundle as below.

## Build the bundle

This installs `slay400`, the generator, as the quickstart does:

<!-- snippet: examples/serve-with-a-mock/steps.sh#install-slay400 -->
```sh
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400-core/main/install.sh | sh
export PATH="$HOME/.slay400/bin:$PATH"
```

It prints:

<!-- snippet: examples/serve-with-a-mock/expected/install-slay400.out -->
```text
slay400 <slay400 version> installed in <workdir>/_home/.slay400/<slay400 version>
Add this to your shell profile, or run it in your current shell:
  export PATH="<workdir>/_home/.slay400/bin:$PATH"
Then check with: slay400 --version
```

Write the repository defaults file (`gateway.yaml`) of the quickstart:

<!-- snippet: examples/serve-with-a-mock/steps.sh#init -->
```sh
slay400 init
```

It prints:

<!-- snippet: examples/serve-with-a-mock/expected/init.out -->
```text
0 errors, 0 warnings, 0 notes
```

Then pack the bundle:

<!-- snippet: examples/serve-with-a-mock/steps.sh#bundle -->
```sh
slay400 bundle --name shop --version 1.0.0 --out build/shop-1.0.0.rpgapi
```

It prints one warning, which the quickstart explains, and no error:

<!-- snippet: examples/serve-with-a-mock/expected/bundle.out -->
```text
apis/ITEMQTY/api.yaml:1:1: warning OVL006: Operation itemqty has no error mapping: a business error reported by the program is returned as a success
0 errors, 1 warning, 0 notes
```

## Install the gateway

This downloads the latest release of the gateway, checks its SHA-256, and unpacks it to `~/.slay400-gateway`. The second line puts it on your `PATH` for this session; add it to your shell profile to keep it there.

<!-- snippet: examples/serve-with-a-mock/steps.sh#install-gateway -->
```sh
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400/main/install.sh | sh
export PATH="$HOME/.slay400-gateway/bin:$PATH"
```

It prints:

<!-- snippet: examples/serve-with-a-mock/expected/install-gateway.out -->
```text
slay400-gateway <version> installed in <workdir>/_home/.slay400-gateway/<version>
Add this to your shell profile, or run it in your current shell:
  export PATH="<workdir>/_home/.slay400-gateway/bin:$PATH"
Then check with: slay400-gateway --version
```

Check it worked:

<!-- snippet: examples/serve-with-a-mock/steps.sh#check-gateway -->
```sh
slay400-gateway --version
```

It prints the version you installed:

<!-- snippet: examples/serve-with-a-mock/expected/check-gateway.out -->
```text
slay400-gateway <version>
```

Nothing else is needed for a mock: JT400 is needed only to call an IBM i.

## Write the configuration

The gateway's configuration file names the tenant (`acme`), its bundle, and one environment (`test`) whose backend is the mock. This writes it as `config.yaml`:

<!-- snippet: examples/serve-with-a-mock/steps.sh#config -->
```sh
cat > config.yaml <<'YAML'
tenants:
  acme:
    bundles: [./build/shop-1.0.0.rpgapi]
    environments:
      test: { code: T0001, backend: mock }
YAML
```

Check it as a start would, without starting:

<!-- snippet: examples/serve-with-a-mock/steps.sh#check -->
```sh
slay400-gateway check --config config.yaml
```

It prints:

<!-- snippet: examples/serve-with-a-mock/expected/check.out -->
```text
slay400-gateway check: config.yaml can be served, 2 operations, 0 warnings
```

If a key is wrong, `check` names the line and the column and a code, such as GWY002 for an unknown key; the [configuration reference](/docs/reference/configuration/) lists every key.

## Start the gateway

This starts the gateway in the background, with what it logs going to `gateway.log`, and waits until it is ready:

<!-- snippet: examples/serve-with-a-mock/steps.sh#start -->
```sh
slay400-gateway run --config config.yaml > gateway.log 2>&1 &
until curl -fs -o /dev/null http://127.0.0.1:8080/health/ready; do sleep 1; done
```

It listens on `127.0.0.1:8080`. The address of every operation starts with the tenant and the environment: `/acme/test/...`.

## Call the two operations

Ask for the status of order 1001:

<!-- snippet: examples/serve-with-a-mock/steps.sh#call-order -->
```sh
curl -s http://127.0.0.1:8080/acme/test/orders/1001/status
```

The mock of `ORDSTATUS` answers:

<!-- snippet: examples/serve-with-a-mock/expected/call-order.out -->
```text
{"status":"S","deliveryDate":"2026-11-20"}
```

Set the quantity of an item. The warehouse is a query parameter, the channel a header, and the quantity is in the JSON body:

<!-- snippet: examples/serve-with-a-mock/steps.sh#call-item -->
```sh
curl -s -X POST 'http://127.0.0.1:8080/acme/test/items/ABC12345/quantity?warehouse=W01' \
  -H 'X-Channel: WEB' -H 'Content-Type: application/json' -d '{"quantity": 5}'
```

The mock of `ITEMQTY` answers:

<!-- snippet: examples/serve-with-a-mock/expected/call-item.out -->
```text
{"onHand":42}
```

## Read the access lines

The gateway writes one JSON line per request. This shows, for each of your two calls, the fields from `tenant` to `status`:

<!-- snippet: examples/serve-with-a-mock/steps.sh#access -->
```sh
grep -o '"tenant".*"status":[0-9]*' gateway.log
```

It prints:

<!-- snippet: examples/serve-with-a-mock/expected/access.out -->
```text
"tenant":"acme","environment":"test","operation":"ordstatus","method":"GET","route":"/orders/{orderNumber}/status","bundle":"shop","bundleVersion":"1.0.0","status":200
"tenant":"acme","environment":"test","operation":"itemqty","method":"POST","route":"/items/{sku}/quantity","bundle":"shop","bundleVersion":"1.0.0","status":200
```

Each line also has `time`, `durationMs` and `correlationId`, which change at every call; the [logs reference](/docs/reference/logs/) explains every field.

## Stop the gateway

This stops the gateway you started in the background:

<!-- snippet: examples/serve-with-a-mock/steps.sh#stop -->
```sh
kill $!
```

To clean up, delete the folder. To remove the two tools as well, delete `~/.slay400` and `~/.slay400-gateway`.

## Next

Call a real program: [Call a program on IBM i](/docs/tutorials/call-a-program-on-ibm-i/).
