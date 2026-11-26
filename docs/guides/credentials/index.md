---
title: Credentials
description: Give an ibmi environment its host, user and password through environment variables, never in the file.
section: guides
slug: credentials
order: 20
appliesTo: "0.1"
---

# Credentials

This page shows how an `ibmi` environment gets its host, user and password from environment variables, and what the gateway says when one is not set or a password is written in the file. It uses `check` only, which opens no connection, so you need no IBM i for it.

## Install the tools, JT400 and the bundle

This installs `slay400`, the generator, and the gateway, as [Serve with a mock](/docs/get-started/serve-with-a-mock/) explains:

<!-- snippet: examples/credentials/steps.sh#install-slay400 -->
```sh
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400-core/main/install.sh | sh
export PATH="$HOME/.slay400/bin:$PATH"
```

<!-- snippet: examples/credentials/steps.sh#install-gateway -->
```sh
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400/main/install.sh | sh
export PATH="$HOME/.slay400-gateway/bin:$PATH"
```

Each prints where it installed the tool and the line that puts it on your `PATH`.

An `ibmi` environment needs JT400 even for `check`, as the [IBM i tutorial](/docs/tutorials/call-a-program-on-ibm-i/) explains:

<!-- snippet: examples/credentials/steps.sh#add-jt400 -->
```sh
mkdir -p "$HOME/.slay400-gateway/ext"
curl -fsSLo "$HOME/.slay400-gateway/ext/jt400-21.0.7.jar" https://repo1.maven.org/maven2/net/sf/jt400/jt400/21.0.7/jt400-21.0.7.jar
echo "d2c06bba78d20b4733a3fabc9fe6ccabac337f7298bea95c6766108fa0b420f8  $HOME/.slay400-gateway/ext/jt400-21.0.7.jar" | sha256sum -c -
export SLAY400_GATEWAY_EXT="$HOME/.slay400-gateway/ext"
```

<!-- snippet: examples/credentials/steps.sh#bundle -->
```sh
slay400 init
slay400 bundle --name shop --version 1.0.0 --out build/shop-1.0.0.rpgapi
```

## Placeholders

A value written `${NAME}` is the value of the environment variable `NAME`, read when the gateway starts. `credentials.user` and `credentials.password` must each be exactly one placeholder; other values, such as `host`, may be one:

<!-- snippet: examples/credentials/steps.sh#config -->
```sh
cat > config.yaml <<'YAML'
tenants:
  acme:
    bundles: [./build/shop-1.0.0.rpgapi]
    environments:
      prod:
        code: P0001
        backend: ibmi
        host: ${IBMI_HOST}
        libraryList: [APPLIB]
        credentials:
          user: ${IBMI_USER}
          password: ${IBMI_PASSWORD}
YAML
```

## A variable that is not set

<!-- snippet: examples/credentials/steps.sh#unset -->
```sh
slay400-gateway check --config config.yaml 2>&1 || echo "exit $?"
```

The gateway names every variable that is missing, with its line and column, and does not start:

<!-- snippet: examples/credentials/expected/unset.out -->
```text
config.yaml:8:15: error GWY007: The environment variable IBMI_HOST is not set
config.yaml:11:17: error GWY007: The environment variable IBMI_USER is not set
config.yaml:12:21: error GWY007: The environment variable IBMI_PASSWORD is not set
3 errors, 0 warnings, 0 notes
slay400-gateway check: config.yaml cannot be served
exit 1
```

## The variables set

In the shell that starts the gateway, set them; the values here are invented:

<!-- snippet: examples/credentials/steps.sh#set -->
```sh
export IBMI_HOST=ibmi.example.invalid IBMI_USER=APIUSER IBMI_PASSWORD=not-a-real-password
slay400-gateway check --config config.yaml
```

It prints:

<!-- snippet: examples/credentials/expected/set.out -->
```text
slay400-gateway check: config.yaml can be served, 2 operations, 0 warnings
```

Set them the way your platform keeps secrets: a secret of the container platform, of the service manager, or of the pipeline. They are never written in a log line or a message of the gateway.

## A password written in the file

This writes a copy of the file with the password written in it:

<!-- snippet: examples/credentials/steps.sh#literal -->
```sh
sed 's/\${IBMI_PASSWORD}/secret/' config.yaml > written.yaml
slay400-gateway check --config written.yaml 2>&1 || echo "exit $?"
```

The gateway refuses it, without showing the value:

<!-- snippet: examples/credentials/expected/literal.out -->
```text
written.yaml:12:21: error GWY015: credentials.password must be a single ${NAME} placeholder for an environment variable; a value written in the configuration file is not accepted
1 error, 0 warnings, 0 notes
slay400-gateway check: written.yaml cannot be served
exit 1
```

The Community Edition has one user and password per tenant and environment; connectors to secret stores are not part of it.
