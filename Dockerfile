# The gateway image (kickoff 08, Part B; kickoff 10). It is built from the distribution that the Maven build writes to
# gateway-server/target/dist; nothing is compiled here. JTOpen is not in it (decision 143): an operator with an ibmi
# environment mounts a folder with the JTOpen jar at /opt/slay400/ext. Build it after ./mvnw -B package:
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
      org.opencontainers.image.licenses="BUSL-1.1" \
      org.opencontainers.image.description="This distribution does not contain JT400 (JTOpen, the IBM Toolbox for Java). The `ibmi` backend needs it at run time. Operators obtain it themselves, under its own licence, the IBM Public License 1.0; the documentation explains how to download and add it."

# A fixed user and group that are not root, with no home and no shell.
RUN groupadd --system --gid 10001 gateway \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin gateway \
    && mkdir -p "/etc/${PRODUCT}" "/var/lib/${PRODUCT}/bundles" "/opt/${PRODUCT}/ext"

# The distribution: the launcher, the libraries, the licence, the notices and the software bill of materials.
COPY gateway-server/target/dist/ /opt/${PRODUCT}/
RUN chmod 0755 "/opt/${PRODUCT}/bin/slay400-gateway"

# The folder for the operator's libraries (JTOpen), a mount; the options of the JVM.
ENV SLAY400_GATEWAY_EXT=/opt/${PRODUCT}/ext \
    JAVA_OPTS="-XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"

USER 10001:10001
WORKDIR /opt/${PRODUCT}

# The API listener and the management listener, on their default ports; the configuration sets the addresses.
EXPOSE 8080 9090

# The launcher execs Java, so that Java is process 1 and receives SIGTERM itself and the gateway stops cleanly within the
# grace period. The command can be replaced, for example by "check --config /etc/slay400/gateway.yaml".
STOPSIGNAL SIGTERM
ENTRYPOINT ["/opt/slay400/bin/slay400-gateway"]
CMD ["run", "--config", "/etc/slay400/gateway.yaml"]
