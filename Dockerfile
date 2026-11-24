# The gateway image (kickoff 08, Part B). It is built from the distribution that the Maven build writes to
# gateway-server/target/dist; nothing is compiled here. Build it after ./mvnw -B package:
#
#   docker build --build-arg REVISION=$(git rev-parse HEAD) -t gateway .
#
# It holds no configuration, no bundle and no secret. Run it with a read-only root file system and a small tmpfs at /tmp,
# where the gateway keeps the private copies of the bundles it loads (see the README).

# The product name, in this one place of the build.
ARG PRODUCT=slay400

# Eclipse Temurin 21.0.13+9, the Java runtime without a JDK, on Ubuntu 24.04 (noble), pinned by the digest of its index.
FROM eclipse-temurin:21.0.13_9-jre-noble@sha256:b4f9d2a0bc273417cf5219b7b832bbf0493ed500b7b57d5001d9f83374659060

ARG PRODUCT
ARG REVISION=unknown
ARG SOURCE=https://github.com/generativearchitects-ai/${PRODUCT}

LABEL org.opencontainers.image.title="${PRODUCT} gateway, Community Edition" \
      org.opencontainers.image.source="${SOURCE}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="BUSL-1.1"

# A fixed user and group that are not root, with no home and no shell.
RUN groupadd --system --gid 10001 gateway \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin gateway \
    && mkdir -p "/etc/${PRODUCT}" "/var/lib/${PRODUCT}/bundles"

# The distribution, the licence, the notices of the third-party components and the software bill of materials.
COPY gateway-server/target/dist/ /opt/gateway/
COPY LICENSE /opt/gateway/LICENSE

ENV GATEWAY_CONFIG=/etc/${PRODUCT}/gateway.yaml

USER 10001:10001
WORKDIR /opt/gateway

# The API listener and the management listener, on their default ports; the configuration sets the addresses.
EXPOSE 8080 9090

# exec: Java is process 1 and receives SIGTERM itself, so that the gateway stops cleanly within the grace period.
STOPSIGNAL SIGTERM
ENTRYPOINT ["/bin/sh", "-c", "exec java -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp -jar /opt/gateway/gateway.jar --config \"$GATEWAY_CONFIG\""]
