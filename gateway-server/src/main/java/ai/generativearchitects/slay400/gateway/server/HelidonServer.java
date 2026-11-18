package ai.generativearchitects.slay400.gateway.server;

import ai.generativearchitects.slay400.gateway.load.Started;
import ai.generativearchitects.slay400.gateway.pipeline.ClientGoneException;
import ai.generativearchitects.slay400.gateway.pipeline.Gateway;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeRequest;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeResponse;
import ai.generativearchitects.slay400.http.ProblemDetails;
import ai.generativearchitects.slay400.http.ProblemKind;
import io.helidon.common.buffers.DataReader;
import io.helidon.common.uri.UriQuery;
import io.helidon.http.DirectHandler;
import io.helidon.http.Header;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.DirectHandlers;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    /** Starts listening on the address and port of the configuration. */
    public static WebServer start(Started started) {
        Gateway gateway = started.gateway();
        return WebServer.builder()
                .host(started.config().host())
                .port(started.config().port())
                .directHandlers(directHandlers())
                .routing(routing -> routing.any((req, res) -> handle(gateway, req, res)))
                .build()
                .start();
    }

    private static void handle(Gateway gateway, ServerRequest req, ServerResponse res) {
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
                () -> readBody(req, gateway.maxBodyBytes())));
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
     * Reads at most {@code max + 1} bytes: one more than the limit is enough to know that the body is over it. A connection that
     * ends or stops sending before the body is complete is {@link ClientGoneException}: Helidon reports the first as
     * {@code InsufficientDataAvailableException}, the second, after the read time-out, as an I/O error.
     */
    private static byte[] readBody(ServerRequest req, int max) {
        try (InputStream in = req.content().inputStream()) {
            return in.readNBytes(max + 1);
        } catch (IOException | UncheckedIOException | DataReader.InsufficientDataAvailableException e) {
            throw new ClientGoneException();
        }
    }

    /** Problem bodies for what Helidon answers by itself. */
    private static DirectHandlers directHandlers() {
        DirectHandler handler = (request, type, status, responseHeaders, message) -> {
            int code = status.code() >= 400 && status.code() < 600 ? status.code() : 500;
            ProblemDetails problem = ProblemKind.forStatus(code).map(kind -> ProblemDetails.of(kind, ""))
                    .orElseGet(() -> ProblemDetails.of(code, status.reasonPhrase(), ""));
            String correlationId = UUID.randomUUID().toString();
            responseHeaders.set(HeaderNames.CONTENT_TYPE, ProblemDetails.CONTENT_TYPE);
            responseHeaders.set(HeaderNames.create("X-Content-Type-Options"), "nosniff");
            responseHeaders.set(HeaderNames.CACHE_CONTROL, "no-store");
            responseHeaders.set(HeaderNames.create(Gateway.CORRELATION_HEADER), correlationId);
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
}
