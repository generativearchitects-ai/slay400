package ai.generativearchitects.slay400.gateway.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What is logged about a request: nothing that came from the client except the correlation id, which has a fixed form, and a
 * method, which is one of a fixed list. Never the path as sent, the query string, headers, bodies, parameter or response
 * values (decision 102).
 *
 * @param time          when the request was received
 * @param tenant        the tenant, if the request named one that exists
 * @param environment   the environment, if the request named one that exists
 * @param operationId   the operation, if a route matched
 * @param method        the method if the gateway serves it, otherwise {@code OTHER}
 * @param routeTemplate the route template, if a route matched
 * @param status        the HTTP status
 * @param durationMs    how long the request took, in milliseconds
 * @param correlationId the correlation id
 * @param problemCodes  the codes of the problems that made the response, if any
 */
public record AccessRecord(Instant time, Optional<String> tenant, Optional<String> environment, Optional<String> operationId, String method,
                           Optional<String> routeTemplate, int status, long durationMs, String correlationId, List<String> problemCodes) {

    /** @throws NullPointerException if an argument is {@code null} */
    public AccessRecord {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(routeTemplate, "routeTemplate");
        Objects.requireNonNull(correlationId, "correlationId");
        problemCodes = List.copyOf(problemCodes);
    }

    /** The one JSON line of this record, without a line break. */
    public String toJson() {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"time\":").append(quote(time.toString()));
        tenant.ifPresent(t -> out.append(",\"tenant\":").append(quote(t)));
        environment.ifPresent(e -> out.append(",\"environment\":").append(quote(e)));
        operationId.ifPresent(o -> out.append(",\"operation\":").append(quote(o)));
        out.append(",\"method\":").append(quote(method));
        routeTemplate.ifPresent(r -> out.append(",\"route\":").append(quote(r)));
        out.append(",\"status\":").append(status).append(",\"durationMs\":").append(durationMs);
        out.append(",\"correlationId\":").append(quote(correlationId));
        if (!problemCodes.isEmpty()) {
            out.append(",\"problems\":[");
            for (int i = 0; i < problemCodes.size(); i++) {
                out.append(i == 0 ? "" : ",").append(quote(problemCodes.get(i)));
            }
            out.append(']');
        }
        return out.append('}').toString();
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
