package ai.generativearchitects.slay400.gateway.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A request as the pipeline sees it, with no HTTP type. The path is the one the client sent, percent-encoded and without the
 * query string. The body is read only when the pipeline reaches the step that needs it, and a supplier must return at most
 * {@code Gateway.maxBodyBytes() + 1} bytes. When the client goes away while the body is read, the supplier throws
 * {@link ClientGoneException}.
 *
 * @param method      the method as sent
 * @param rawPath     the path as sent, starting with {@code /}
 * @param query       the decoded query parameters by name, each with its values in order
 * @param headers     the header values by name
 * @param contentType the value of the content type header, if any
 * @param body        reads the body
 */
public record RuntimeRequest(String method, String rawPath, Map<String, List<String>> query, Map<String, List<String>> headers,
                             Optional<String> contentType, Supplier<byte[]> body) {

    /** @throws NullPointerException if an argument is {@code null} */
    public RuntimeRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(rawPath, "rawPath");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(body, "body");
    }
}
