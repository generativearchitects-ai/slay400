<p align="center">
  <img src="docs/assets/slay400-banner.svg" alt="slay400. RPG slay: turn your IBM i programs into REST, async and AI-ready APIs." width="100%">
</p>

# slay400

slay400 turns RPG programs on IBM i (AS/400) into managed, AI-ready APIs, generated from the PCML the compiler already produces.

This repository holds the gateway runtime, Community Edition: it interprets the `descriptor.json` of a bundle built by [slay400-core](https://github.com/generativearchitects-ai/slay400-core) and serves the generated APIs.

**Status: early development.** Nothing here is released.

## Run it

Install the latest release into `~/.slay400-gateway` (on Windows, `install.ps1`); it needs Java 21 or later:

```
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400/main/install.sh | sh
export PATH="$HOME/.slay400-gateway/bin:$PATH"
```

```
slay400-gateway run --config config.yaml      # start; --config config.yaml alone does the same
slay400-gateway check --config config.yaml    # check the configuration and the bundles as a start does, then exit
slay400-gateway --version
slay400-gateway help
```

`check` opens no connection to an IBM i. `JAVA_OPTS` and `SLAY400_GATEWAY_OPTS` go to the JVM. Exit codes: 0, 1 when the
start or the check finds an error, 2 for a usage error, 70 when no Java 21 or later is found, 143 after `SIGTERM`.

The build writes the same distribution as `gateway-server/target/slay400-gateway-<version>.tar.gz` and `.zip`: one folder
with `bin/` (the launchers), `lib/` (the gateway, `lib/ibmi/` the ibmi backend, `lib/ext/` empty, for JTOpen), `LICENSE`,
`THIRD-PARTY-NOTICES.md`, `bom.json` and `VERSION`.

## Documentation

The documentation is in [`docs/`](https://github.com/generativearchitects-ai/slay400/blob/main/docs), with every command and output taken from an example that the build runs:

- [Serve with a mock](https://github.com/generativearchitects-ai/slay400/blob/main/docs/get-started/serve-with-a-mock/index.md): the quickstart's bundle, served and called, without an IBM i.
- [Call a program on IBM i](https://github.com/generativearchitects-ai/slay400/blob/main/docs/tutorials/call-a-program-on-ibm-i/index.md) and [Publish a new version](https://github.com/generativearchitects-ai/slay400/blob/main/docs/tutorials/publish-a-new-version/index.md).
- Guides: [Tenants and environments](https://github.com/generativearchitects-ai/slay400/blob/main/docs/guides/tenants-and-environments/index.md), [Credentials](https://github.com/generativearchitects-ai/slay400/blob/main/docs/guides/credentials/index.md),
  [TLS and the management listener](https://github.com/generativearchitects-ai/slay400/blob/main/docs/guides/tls-and-metrics/index.md).
- Reference: [configuration](https://github.com/generativearchitects-ai/slay400/blob/main/docs/reference/configuration/index.md), [URLs and routes](https://github.com/generativearchitects-ai/slay400/blob/main/docs/reference/urls-and-routes/index.md),
  [errors](https://github.com/generativearchitects-ai/slay400/blob/main/docs/reference/errors/index.md), [logs](https://github.com/generativearchitects-ai/slay400/blob/main/docs/reference/logs/index.md).
- Concepts: [How a call works](https://github.com/generativearchitects-ai/slay400/blob/main/docs/concepts/how-a-call-works/index.md).

## JTOpen for the ibmi backend

This distribution does not contain JT400 (JTOpen, the IBM Toolbox for Java). The `ibmi` backend needs it at run time.
Operators obtain it themselves, under its own licence, the IBM Public License 1.0; the documentation explains how to download
and add it.

The image does not contain it either. A gateway with `mock` environments only does not need it.

Download the verified version from Maven Central into `lib/ext/` of the distribution, and check its SHA-256:

```
curl -fLo lib/ext/jt400-21.0.7.jar https://repo1.maven.org/maven2/net/sf/jt400/jt400/21.0.7/jt400-21.0.7.jar
echo "d2c06bba78d20b4733a3fabc9fe6ccabac337f7298bea95c6766108fa0b420f8  lib/ext/jt400-21.0.7.jar" | sha256sum -c -
```

On macOS, `shasum -a 256 -c -` in place of `sha256sum -c -`. The gateway itself downloads nothing.

- The folder is `lib/ext/` beside the distribution, or the folder that `SLAY400_GATEWAY_EXT` names. Only the files directly
  in it are looked at, only at start; a jar is JTOpen when it holds `com/ibm/as400/access/AS400.class`. It is loaded with the
  `ibmi` backend, by a class loader of their own; nothing else of the folder is loaded. The start line names the folder and
  the version found: `"ext":"…/lib/ext","jt400":"21.0.7"`.
- **Verified versions:** 21.0.7. Another version starts, with the warning GWY028.
- **No JTOpen** and an `ibmi` environment: the start (and `check`) stops with **GWY026**, which names the folder and this
  section. More than one JTOpen jar in the folder: GWY027. Other files in it are not loaded, with the warning GWY029.

## The container image

The `Dockerfile` builds an image from the distribution of the Maven build; nothing is compiled in the image. The base image
is Eclipse Temurin 21 (the Java runtime without a JDK) on Ubuntu 24.04, pinned by digest. The image runs as user 10001, needs
no write access except a temporary folder, and holds no configuration, no bundle, no secret and no JTOpen. Its entry point is
the launcher, with the command `run --config /etc/slay400/gateway.yaml`; `docker run … gateway check --config
/etc/slay400/gateway.yaml` checks instead. It is not published.

```
./mvnw -B package -DskipTests
docker build --build-arg REVISION=$(git rev-parse HEAD) -t gateway .
```

Fixed places in the container:

| Path | What |
|---|---|
| `/etc/slay400/gateway.yaml` | the configuration; without it the gateway stops with GWY001 |
| `/var/lib/slay400/bundles` | a folder for the bundles (name it in `bundles`) |
| `/opt/slay400/ext` | the folder for JTOpen (`SLAY400_GATEWAY_EXT`): mount a folder that holds `jt400-21.0.7.jar`, for an `ibmi` environment |
| `/tmp` | the private copies of the bundles while they load: mount a small `tmpfs` |

A configuration for the container listens on all addresses, and names the bundle folder and the certificate:

```yaml
server:
  address: 0.0.0.0
  port: 8443
  tls:
    certificate: /etc/slay400/tls/tls.crt
    key: /etc/slay400/tls/tls.key
management:
  address: 0.0.0.0
  port: 9090
tenants:
  acme:
    bundles: [/var/lib/slay400/bundles]
    environments:
      test: { code: T0001, backend: mock }
```

```
docker run --read-only --tmpfs /tmp:rw,size=300m \
  -v $PWD/etc:/etc/slay400:ro \
  -v $PWD/bundles:/var/lib/slay400/bundles:ro \
  -v $PWD/ext:/opt/slay400/ext:ro \
  -p 8443:8443 -p 127.0.0.1:9090:9090 \
  gateway
```

The `ext` mount is needed only with an `ibmi` environment; `ext/` holds the JTOpen jar downloaded as above.

`etc/` holds `gateway.yaml` and `tls/tls.crt` and `tls/tls.key`, readable by user 10001. The `tmpfs` needs room for the
largest set of bundles the gateway loads at once (each file is at most 132 MiB). New bundles and a new certificate are
published by writing them into the mounted folders; the gateway takes them without a restart.

**Stopping.** On `SIGTERM` (`docker stop`) the listeners stop taking connections, the requests in flight get up to 5 seconds
to finish, then the poll thread stops and the backends close. The process exits with code 143. The default grace period of
`docker stop`, 10 seconds, is enough.

The project was called glow400 until 2026-11-16.

Licence: Business Source License 1.1 with an Additional Use Grant.

The slay400 name and logo are not licensed under the Business Source License; see `docs/assets/README.md`.
