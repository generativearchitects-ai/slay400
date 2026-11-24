<p align="center">
  <img src="docs/assets/slay400-banner.svg" alt="slay400. RPG slay: turn your IBM i programs into REST, async and AI-ready APIs." width="100%">
</p>

# slay400

slay400 turns RPG programs on IBM i (AS/400) into managed, AI-ready APIs, generated from the PCML the compiler already produces.

This repository holds the gateway runtime, Community Edition: it interprets the `descriptor.json` of a bundle built by [slay400-core](https://github.com/generativearchitects-ai/slay400-core) and serves the generated APIs.

**Status: early development.** Nothing here is released.

## Running the gateway

The gateway reads one configuration file at start:

```yaml
server:
  address: 127.0.0.1          # default
  port: 8080                  # default
management:
  address: 127.0.0.1          # default
  port: 9090                  # default; 0 turns the management listener off
reload:
  intervalSeconds: 10         # default; 0 turns reloading off; at most 3600
tenants:
  acme:
    bundles:
      - ./bundles             # a folder: every *.rpgapi file directly in it, in name order
      - ./extra/orders.rpgapi # or one bundle file
    environments:
      test: { code: T0001, backend: mock }
```

```
java -jar gateway-server/target/dist/gateway.jar --config gateway.yaml
```

### The listeners

- **The API listener** (`server`) serves the operations and `/health/live` and `/health/ready`. Until kickoff 08 its address
  was `server.host`; that key is now refused at start with a message that names `server.address`.
- **The management listener** (`management`, plain HTTP) serves `/health/live`, `/health/ready` and `/metrics`, and never an
  operation. The API listener never serves `/metrics`. Both listeners have the same limits (request line, headers, body size,
  idle time, connections).

### HTTPS

```yaml
server:
  address: 0.0.0.0
  port: 8443
  tls:
    certificate: /etc/slay400/tls/tls.crt   # PEM: the certificate, then its chain
    key: /etc/slay400/tls/tls.key           # PEM, PKCS#8 (-----BEGIN PRIVATE KEY----- or ENCRYPTED PRIVATE KEY)
    keyPassword: ${TLS_KEY_PASSWORD}        # only for an encrypted key; a ${NAME} placeholder, never a value
```

With `server.tls` the API listener speaks HTTPS only, TLS 1.3 and 1.2, with the JDK's cipher suites; no client certificate is
asked for. A key in another PEM form (`-----BEGIN RSA PRIVATE KEY-----`) is converted with `openssl pkcs8 -topk8`.

A certificate or key that is missing, unreadable, not PEM, not matching each other, expired or not valid yet stops the start
(GWY022 to GWY024); a certificate that expires within 30 days is a warning (GWY025). A password written in the file stops the
start (GWY015). No message shows key material or the password.

**Rotation.** The two files are polled with the bundle files (`reload.intervalSeconds`), with the same rules: a new pair is
taken once it is the same at two polls in a row, and is used for new connections; a pair that cannot be used is refused with
one warning, and the pair in use stays.

### Loading bundles without restart

While it runs, the gateway looks at its bundle files every `reload.intervalSeconds` and serves a new set of bundles without a
restart:

- **A change** is a file added, removed, or with another size or time of last change, and a different content: a file
  copied again with the same content is not a change.
- **It waits until the files are still.** A changed set is loaded only once it is the same at two polls in a row, so a file
  being copied is not loaded half-way. Each file is then read once, into a private copy. A file over 132 MiB, more than
  any bundle can be, is refused without being copied.
- **All or nothing.** The new set is checked as a start checks it. If anything fails, the running set stays and one warning
  gives the diagnostics; the same set is not tried again until the files change again.
- **One switch.** A request is served entirely by the set that was current when it arrived; requests in flight finish on the
  old set; none fails or waits because of a switch. Each switch is one line: `{"event":"reloaded",...}` with the bundles and
  versions served, the operations added, removed and changed, and how long the load took. The access line of a request names
  the bundle and the version that served it.
- **Removal.** A bundle removed from a folder is no longer served: its routes answer 404. A file named in the configuration
  must stay; if it is gone, the new set is refused. A tenant always keeps at least one bundle.
- The connections and credentials of the environments are kept across switches.
- Polling is the only trigger: there is no endpoint, signal or command, and no file watcher of the operating system.

**The configuration file is read once, at start.** Tenants, environments, credentials, limits and the `reload` section itself
change with a restart only.

To publish a new version, copy it next to the folder under another name and rename it into place, or copy it over the old
file: either way the gateway waits until the file is still.

### Try it with the shop fixture

```
./mvnw -B package -DskipTests
mkdir -p demo/bundles
./mvnw -B -f fixtures/shop/pom.xml package -Dpcml.attach=false -Dpcml.bundleFile=$PWD/demo/shop-1.0.0.rpgapi
./mvnw -B -f fixtures/shop/pom.xml package -Dpcml.attach=false -Dpcml.bundleVersion=1.1.0 -Dpcml.bundleFile=$PWD/demo/shop-1.1.0.rpgapi
cp demo/shop-1.0.0.rpgapi demo/bundles/shop.rpgapi
printf 'reload:\n  intervalSeconds: 1\ntenants:\n  acme:\n    bundles: [./bundles]\n    environments:\n      test: { code: T0001, backend: mock }\n' > demo/gateway.yaml
java -jar gateway-server/target/dist/gateway.jar --config demo/gateway.yaml
```

In a second terminal, send requests and publish the second version while they run:

```
while true; do curl -s -o /dev/null -w '%{http_code} ' http://127.0.0.1:8080/acme/test/orders/1001/status; sleep 0.1; done
cp demo/shop-1.1.0.rpgapi demo/bundles/shop.rpgapi.part && mv demo/bundles/shop.rpgapi.part demo/bundles/shop.rpgapi
```

Every request answers 200. About two seconds after the copy the gateway logs
`{"event":"reloaded","bundles":[{"tenant":"acme","name":"shop","version":"1.1.0"}],...}`, and from then on the access lines say
`"bundleVersion":"1.1.0"`.

The project was called glow400 until 2026-11-16.

Licence: Business Source License 1.1 with an Additional Use Grant.

The slay400 name and logo are not licensed under the Business Source License; see `docs/assets/README.md`.
