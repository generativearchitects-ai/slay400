package ai.generativearchitects.slay400.gateway.pipeline;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the pipeline answers, with no HTTP type.
 *
 * @param status      the HTTP status
 * @param headers     the headers to send, by name, apart from the content type
 * @param contentType the content type, if there is a body
 * @param body        the body; empty if there is none
 */
public record RuntimeResponse(int status, Map<String, String> headers, Optional<String> contentType, byte[] body) {

    /** @throws NullPointerException if an argument is {@code null} */
    public RuntimeResponse {
        headers = Map.copyOf(headers);
        Objects.requireNonNull(contentType, "contentType");
        body = Objects.requireNonNull(body, "body").clone();
    }

    /** A copy of the body. */
    @Override
    public byte[] body() {
        return body.clone();
    }
}
