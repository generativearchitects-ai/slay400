package ai.generativearchitects.slay400.gateway.server;

import ai.generativearchitects.slay400.gateway.config.ManagementConfig;
import ai.generativearchitects.slay400.gateway.load.TlsWatcher;
import ai.generativearchitects.slay400.gateway.tls.TlsMaterial;
import io.helidon.common.tls.Tls;
import io.helidon.webserver.ListenerConfig;
import io.helidon.webserver.WebServerConfig;
import ai.generativearchitects.slay400.gateway.load.Started;
import ai.generativearchitects.slay400.gateway.pipeline.ClientGoneException;
import ai.generativearchitects.slay400.gateway.pipeline.Gateway;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeRequest;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeResponse;
import ai.generativearchitects.slay400.http.ProblemDetails;
import ai.generativearchitects.slay400.http.ProblemKind;
import io.helidon.common.uri.UriQuery;
import io.helidon.http.DirectHandler;
import io.helidon.http.Header;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http1.Http1Config;
import io.helidon.webserver.http.DirectHandlers;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Helidon adapter: it turns a Helidon request into a {@link RuntimeRequest}, hands it to the {@link Gateway}, and sends the
 * answer. Requests are handled on virtual threads, which is Helidon's default.
 *
 * <p>Errors that Helidon raises before a request reaches the pipeline (a bad request line, a request it refuses at transport
 * level, an internal error) are given the same shape: a problem body with the headers of every response.
 */
public final class HelidonServer {

    private HelidonServer() {
    }

    /** The name of the management listener's socket. */
    static final String MANAGEMENT = "management";

    /** Starts the listeners of the configuration, with the {@link ServerLimits#DEFAULTS}. */
    public static WebServer start(Started started) {
        return start(started, ServerLimits.DEFAULTS);
    }

    /**
     * Starts the API listener on the address and port of the configuration, with TLS when {@code server.tls} is set, and the
     * management listener unless its port is 0 (kickoff 08). Both have these limits. The management listener answers the health
     * paths only; it never serves an operation. A new certificate and key taken by the {@link TlsWatcher} replace those of the
     * API listener for new connections.
     */
    static WebServer start(Started started, ServerLimits limits) {
        Gateway gateway = started.gateway();
        WebServerConfig.Builder builder = WebServer.builder();
        listener(builder, limits, gateway)
                .host(started.config().address())
                .port(started.config().port())
                .routing(routing -> routing.any((req, res) -> handle(gateway, limits, req, res)));
        started.tls().ifPresent(watcher -> builder.tls(tls(watcher.current())));
        ManagementConfig management = started.config().management();
        if (management.enabled()) {
            builder.putSocket(MANAGEMENT, socket -> listener(socket, limits, gateway).host(management.address()).port(management.port()));
            builder.routing(MANAGEMENT, routing -> routing.any((req, res) -> management(gateway, limits, req, res)));
        }
        WebServer server = builder.build().start();
        started.tls().ifPresent(watcher -> watcher.onChange(material -> server.reloadTls(tls(material))));
        return server;
    }

    /** The TLS of the API listener: this certificate and key, TLS 1.3 and 1.2 only, the JDK's cipher suites, no client certificate. */
    static Tls tls(TlsMaterial material) {
        // Key and chain rather than a ready context: Helidon builds its context with a key manager it can replace in a running
        // listener, which a context given to it it cannot.
        return Tls.builder().privateKey(material.privateKey()).privateKeyCertChain(material.certificateChain()).enabledProtocols(TlsMaterial.PROTOCOLS)
                .build();
    }

    /** What both listeners have: the limits, HTTP/1 only, and the problem bodies of what Helidon answers by itself. */
    private static <B extends ListenerConfig.BuilderBase<B, ?>> B listener(B builder, ServerLimits limits, Gateway gateway) {
        return builder
                .maxPayloadSize(limits.maxPayloadBytes())
                .maxTcpConnections(limits.maxConnections())
                .idleConnectionTimeout(limits.idleTimeout())
                .idleConnectionPeriod(limits.idleCheckPeriod())
                // Helidon 4.5.7 declares this option but does not apply it to accepted connections; set so that it applies if it
                // ever does. The body has its own deadline, below.
                .connectionOptions(options -> options.readTimeout(limits.readTimeout()))
                // The only protocol is HTTP/1 with these limits. A second, discovered one would make Helidon close a malformed
                // request line without an answer instead of answering it 400.
                .protocolsDiscoverServices(false)
                .addProtocol(Http1Config.builder().maxPrologueLength(limits.maxRequestLineBytes()).maxHeadersSize(limits.maxHeadersBytes()).build())
                .directHandlers(directHandlers(gateway));
    }

    /** The management listener: the health paths, through the pipeline as on the API listener; anything else is 404. */
    private static void management(Gateway gateway, ServerLimits limits, ServerRequest req, ServerResponse res) {
        String path = req.prologue().uriPath().rawPath();
        if (path.equals("/health/live") || path.equals("/health/ready")) {
            handle(gateway, limits, req, res);
            return;
        }
        String correlationId = UUID.randomUUID().toString();
        ProblemDetails problem = ProblemDetails.of(ProblemKind.NOT_FOUND, "");
        gateway.refused(req.prologue().method().text(), problem.status(), correlationId, slug(problem.type()));
        res.status(Status.create(problem.status()));
        res.header("X-Content-Type-Options", "nosniff");
        res.header("Cache-Control", "no-store");
        res.header(Gateway.CORRELATION_HEADER, correlationId);
        res.header(HeaderNames.CONTENT_TYPE.defaultCase(), ProblemDetails.CONTENT_TYPE);
        res.send(problem.withContext("", "", correlationId).toJson());
    }

    private static void handle(Gateway gateway, ServerLimits limits, ServerRequest req, ServerResponse res) {
        String path = req.prologue().uriPath().rawPath();
        UriQuery query = req.query();
        Map<String, List<String>> queryValues = new LinkedHashMap<>();
        for (String name : query.names()) {
            queryValues.put(name, query.all(name));
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Header header : req.headers()) {
            headers.computeIfAbsent(header.name(), name -> new ArrayList<>()).addAll(header.allValues());
        }
        Optional<String> contentType = req.headers().first(HeaderNames.CONTENT_TYPE);
        RuntimeResponse response = gateway.handle(new RuntimeRequest(req.prologue().method().text(), path, queryValues, headers, contentType,
                () -> readBody(req, gateway.maxBodyBytes(), limits.readTimeout())));
        res.status(Status.create(response.status()));
        response.headers().forEach(res::header);
        response.contentType().ifPresent(type -> res.header(HeaderNames.CONTENT_TYPE.defaultCase(), type));
        byte[] body = response.body();
        if (body.length == 0 || req.prologue().method().text().equals("HEAD")) {
            res.send();
        } else {
            res.send(body);
        }
    }

    /**
     * Reads at most {@code max + 1} bytes: one more than the limit is enough to know that the body is over it. The whole body must
     * arrive within {@code deadline}. The bytes are read on a virtual thread of their own; when the deadline passes, that thread is
     * interrupted, which closes the connection. A connection that ends, or a body that does not arrive in time, is
     * {@link ClientGoneException}.
     */
    static byte[] readBody(ServerRequest req, int max, Duration deadline) {
        return readWithDeadline(() -> {
            try (InputStream in = req.content().inputStream()) {
                return in.readNBytes(max + 1);
            }
        }, deadline);
    }

    /** Runs a blocking read on a virtual thread of its own and waits for it at most {@code deadline}. */
    static byte[] readWithDeadline(Callable<byte[]> read, Duration deadline) {
        FutureTask<byte[]> task = new FutureTask<>(read);
        Thread reader = Thread.ofVirtual().name("body-reader").start(task);
        try {
            return task.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            reader.interrupt();
            throw new ClientGoneException();
        } catch (ExecutionException e) {
            // Helidon reports a connection that ended as InsufficientDataAvailableException, other failures as I/O errors.
            throw new ClientGoneException();
        } catch (InterruptedException e) {
            reader.interrupt();
            Thread.currentThread().interrupt();
            throw new ClientGoneException();
        }
    }

    /** Problem bodies, and an access line without tenant, for what Helidon answers by itself. */
    private static DirectHandlers directHandlers(Gateway gateway) {
        DirectHandler handler = (request, type, status, responseHeaders, message) -> {
            int code = status.code() >= 400 && status.code() < 600 ? status.code() : 500;
            ProblemDetails problem = ProblemKind.forStatus(code).map(kind -> ProblemDetails.of(kind, ""))
                    .orElseGet(() -> ProblemDetails.of(code, status.reasonPhrase(), ""));
            String correlationId = UUID.randomUUID().toString();
            responseHeaders.set(HeaderNames.CONTENT_TYPE, ProblemDetails.CONTENT_TYPE);
            responseHeaders.set(HeaderNames.create("X-Content-Type-Options"), "nosniff");
            responseHeaders.set(HeaderNames.CACHE_CONTROL, "no-store");
            responseHeaders.set(HeaderNames.create(Gateway.CORRELATION_HEADER), correlationId);
            gateway.refused(request.method(), problem.status(), correlationId, slug(problem.type()));
            return DirectHandler.TransportResponse.builder()
                    .status(Status.create(problem.status()))
                    .headers(responseHeaders)
                    .entity(problem.withContext("", "", correlationId).toJson())
                    .keepAlive(type.keepAlive())
                    .build();
        };
        DirectHandlers.Builder builder = DirectHandlers.builder();
        for (DirectHandler.EventType type : DirectHandler.EventType.values()) {
            builder.addHandler(type, handler);
        }
        return builder.build();
    }

    /** The last segment of a problem type, or the type itself when it is {@code about:blank}. */
    static String slug(String type) {
        return type.substring(type.lastIndexOf('/') + 1);
    }
}
