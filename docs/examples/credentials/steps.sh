#!/bin/sh
# Credentials: placeholders for environment variables, and what the gateway says when one is missing or a value is
# written in the file. Only check is used: it opens no connection.
set -eu

# region: install-slay400
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400-core/main/install.sh | sh
export PATH="$HOME/.slay400/bin:$PATH"
# endregion

# region: install-gateway
curl -fsSL https://raw.githubusercontent.com/generativearchitects-ai/slay400/main/install.sh | sh
export PATH="$HOME/.slay400-gateway/bin:$PATH"
# endregion

# region: add-jt400
mkdir -p "$HOME/.slay400-gateway/ext"
curl -fsSLo "$HOME/.slay400-gateway/ext/jt400-21.0.7.jar" https://repo1.maven.org/maven2/net/sf/jt400/jt400/21.0.7/jt400-21.0.7.jar
echo "d2c06bba78d20b4733a3fabc9fe6ccabac337f7298bea95c6766108fa0b420f8  $HOME/.slay400-gateway/ext/jt400-21.0.7.jar" | sha256sum -c -
export SLAY400_GATEWAY_EXT="$HOME/.slay400-gateway/ext"
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
      prod:
        code: P0001
        backend: ibmi
        host: ${IBMI_HOST}
        libraryList: [APPLIB]
        credentials:
          user: ${IBMI_USER}
          password: ${IBMI_PASSWORD}
YAML
# endregion

# region: unset
slay400-gateway check --config config.yaml 2>&1 || echo "exit $?"
# endregion

# region: set
export IBMI_HOST=ibmi.example.invalid IBMI_USER=APIUSER IBMI_PASSWORD=not-a-real-password
slay400-gateway check --config config.yaml
# endregion

# region: literal
sed 's/\${IBMI_PASSWORD}/secret/' config.yaml > written.yaml
slay400-gateway check --config written.yaml 2>&1 || echo "exit $?"
# endregion
