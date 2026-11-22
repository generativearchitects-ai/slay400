package ai.generativearchitects.slay400.gateway.pipeline;

import ai.generativearchitects.slay400.gateway.call.CallContext;
import ai.generativearchitects.slay400.gateway.call.CallOutcome;
import ai.generativearchitects.slay400.http.HttpBinding;
import ai.generativearchitects.slay400.http.ProblemDetails;
import ai.generativearchitects.slay400.http.ProblemKind;
import ai.generativearchitects.slay400.http.ReadLimits;
import ai.generativearchitects.slay400.http.RequestParts;
import ai.generativearchitects.slay400.http.RequestResult;
import ai.generativearchitects.slay400.http.RouteMatch;
import ai.generativearchitects.slay400.pcml.api.HttpMethod;
import ai.generativearchitects.slay400.pcml.codec.ApiValue;
import ai.generativearchitects.slay400.pcml.codec.DecodeResult;
import ai.generativearchitects.slay400.pcml.codec.EncodeResult;
import ai.generativearchitects.slay400.pcml.codec.ValueProblem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A gateway that has started: the request pipeline over what the start loaded. It has no HTTP type; the server adapter turns
 * its own requests into {@link RuntimeRequest} and sends the {@link RuntimeResponse}.
 *
 * <p>For {@code /{tenant}/{environment}/<path>}: tenant and environment (404), route (404, or 405 with {@code Allow}), request
 * (400), encoding (400), call (503, 504, or 502), decoding (502), business error (the mapped status), response. Every error
 * body is {@code application/problem+json}. An exception nobody expected is a 500 with no detail from the exception, and is
 * logged with its stack trace. {@link #handle} never throws.
 */
public final class Gateway {

    /** The header that carries the correlation id, in both directions. */
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    /** The code of the codec for a response over the limit of its bundle. */
    static final String RESPONSE_LIMIT = "VAL104";

    /** The problem recorded in the access line when the client went away while its body was read. */
    public static final String CLIENT_GONE = "client-gone";

    private static final System.Logger LOG = System.getLogger("slay400.gateway");
    private static final Pattern CORRELATION = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final byte[] HEALTH_BODY = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);

    private final Map<String, TenantRuntime> tenants;
    private final ReadLimits limits;
    private final Consumer<String> accessLog;

    /**
     * @param tenants   the tenants by name
     * @param limits    what a request body may be
     * @param accessLog receives the one JSON line of every request
     */
    public Gateway(Map<String, TenantRuntime> tenants, ReadLimits limits, Consumer<String> accessLog) {
        this.tenants = Map.copyOf(tenants);
        this.limits = Objects.requireNonNull(limits, "limits");
        this.accessLog = Objects.requireNonNull(accessLog, "accessLog");
    }

    /** The largest request body, in bytes; a reader of the body reads at most this plus one. */
    public int maxBodyBytes() {
        return limits.maxBodyBytes();
    }

    /** The limits applied to a request body. */
    public ReadLimits limits() {
        return limits;
    }

    /** Handles a request. Never throws. */
    public RuntimeResponse handle(RuntimeRequest request) {
        long started = System.nanoTime();
        Instant time = Instant.now();
        String correlationId = correlationId(request.headers());
        Trace trace = new Trace();
        RuntimeResponse response;
        try {
            response = process(request, correlationId, trace);
        } catch (ClientGoneException e) {
            // Nobody receives this answer; the access line still records the request.
            LOG.log(System.Logger.Level.INFO, "The client went away while its body was read, correlation id " + correlationId);
            trace.problems.clear();
            trace.problems.add(CLIENT_GONE);
            response = problem(ProblemDetails.of(ProblemKind.REQUEST_INVALID, ""), trace, correlationId, Map.of());
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            LOG.log(System.Logger.Level.ERROR, "Unexpected error, correlation id " + correlationId, e);
            trace.problems.clear();
            trace.problems.add(ProblemKind.INTERNAL_ERROR.type().substring(ProblemKind.INTERNAL_ERROR.type().lastIndexOf('/') + 1));
            response = problem(ProblemDetails.of(ProblemKind.INTERNAL_ERROR, ""), trace, correlationId, Map.of());
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        access(new AccessRecord(time, Optional.ofNullable(trace.tenant), Optional.ofNullable(trace.environment),
                Optional.ofNullable(trace.operationId), method(request.method()), Optional.ofNullable(trace.route), response.status(), millis,
                correlationId, trace.problems));
        return response;
    }

    /**
     * Writes the access line of a request that the server refused before it reached the pipeline (a malformed request line,
     * headers over the limit, a body declared over the server's limit). The line has no tenant, environment, operation or
     * route, since none was resolved, and a duration of 0, since the server does not say when the request began. Never throws.
     *
     * @param method        the method as the server read it, possibly empty; logged only if the gateway serves it
     * @param status        the status of the answer
     * @param correlationId the correlation id of the answer
     * @param problem       the slug of the problem type of the answer, or {@code about:blank}
     */
    public void refused(String method, int status, String correlationId, String problem) {
        try {
            access(new AccessRecord(Instant.now(), Optional.empty(), Optional.empty(), Optional.empty(), method(method == null ? "" : method),
                    Optional.empty(), status, 0, correlationId, List.of(problem)));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "The access log failed", e);
        }
    }

    private void access(AccessRecord record) {
        try {
            accessLog.accept(record.toJson());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "The access log failed", e);
        }
    }

    private static final class Trace {
        String tenant;
        String environment;
        String operationId;
        String route;
        final List<String> problems = new ArrayList<>();
    }

    private RuntimeResponse process(RuntimeRequest request, String correlationId, Trace trace) {
        String path = request.rawPath();
        if (path.equals("/health/live") || path.equals("/health/ready")) {
            return health(request, correlationId);
        }
        // /{tenant}/{environment}/<rest>
        String[] parts = path.startsWith("/") ? path.substring(1).split("/", 3) : new String[0];
        TenantRuntime tenant = parts.length >= 2 ? tenants.get(parts[0]) : null;
        EnvironmentRuntime environment = tenant == null ? null : tenant.environments().get(parts[1]);
        if (environment == null) {
            return problem(ProblemDetails.of(ProblemKind.NOT_FOUND, ""), trace, correlationId, Map.of());
        }
        trace.tenant = tenant.name();
        trace.environment = environment.name();
        String rest = parts.length == 3 ? "/" + parts[2] : "";
        Optional<HttpMethod> method = parse(request.method());
        if (method.isEmpty()) {
            Set<HttpMethod> served = served(tenant, rest);
            return served.isEmpty() ? problem(ProblemDetails.of(ProblemKind.NOT_FOUND, ""), trace, correlationId, Map.of())
                    : notAllowed(served, trace, correlationId);
        }
        RouteMatch match = tenant.routes().match(method.get(), rest);
        if (match instanceof RouteMatch.MethodNotAllowed notAllowed) {
            return notAllowed(notAllowed.allowed(), trace, correlationId);
        }
        if (!(match instanceof RouteMatch.Found found)) {
            return problem(ProblemDetails.of(ProblemKind.NOT_FOUND, ""), trace, correlationId, Map.of());
        }
        CompiledOperation compiled = tenant.operations().get(found.operation());
        trace.operationId = found.operation().id();
        trace.route = found.operation().route();

        byte[] body = request.body().get();
        RequestResult read = compiled.binding().readRequest(
                new RequestParts(found.pathParameters(), request.query(), request.headers(), request.contentType(), body));
        if (read.values().isEmpty()) {
            return invalid(read.problems(), trace, correlationId);
        }
        Map<String, String> injected = environment.injected().getOrDefault(found.operation(), Map.of());
        EncodeResult encoded = compiled.codec().encode(read.values().get(), injected);
        if (encoded.parameters().isEmpty()) {
            return invalid(encoded.problems(), trace, correlationId);
        }
        CallOutcome outcome = environment.caller().call(new CallContext(tenant.name(), environment.name(), correlationId),
                found.operation(), encoded.parameters().get());
        switch (outcome) {
            case CallOutcome.Unavailable ignored -> {
                return kind(ProblemKind.UNAVAILABLE, trace, correlationId);
            }
            case CallOutcome.TimedOut ignored -> {
                return kind(ProblemKind.TIMEOUT, trace, correlationId);
            }
            case CallOutcome.Failed failed -> {
                LOG.log(System.Logger.Level.WARNING, "Program call failed, tenant " + tenant.name() + ", environment " + environment.name() + ", correlation id "
                        + correlationId + ": " + failed.reason());
                return kind(ProblemKind.PROGRAM_CALL_FAILED, trace, correlationId);
            }
            case CallOutcome.Returned returned -> {
                DecodeResult decoded = compiled.codec().decode(returned.output());
                if (!decoded.problems().isEmpty()) {
                    decoded.problems().forEach(p -> trace.problems.add(p.code()));
                    if (decoded.problems().stream().anyMatch(p -> p.code().equals(RESPONSE_LIMIT))) {
                        LOG.log(System.Logger.Level.WARNING, "The response of the operation " + found.operation().id() + " has more values than the limit of "
                                + compiled.maxResponseValues() + " of its bundle, tenant " + tenant.name() + ", environment " + environment.name() + ", correlation id "
                                + correlationId);
                    }
                    return problem(ProblemDetails.of(ProblemKind.PROGRAM_CALL_FAILED, "The program returned data that the API cannot represent."),
                            trace, correlationId, Map.of());
                }
                if (decoded.error().isPresent()) {
                    trace.problems.add("program-error");
                    return problem(ProblemDetails.forBusinessError(decoded.error().get()), trace, correlationId, Map.of());
                }
                Map<String, ApiValue> values = decoded.response().orElse(Map.of());
                HttpBinding.Success success = compiled.binding().success();
                byte[] json = success.contentType().isPresent() ? compiled.binding().writeResponse(values) : new byte[0];
                return new RuntimeResponse(success.status(), common(correlationId, Map.of()), success.contentType(), json);
            }
        }
    }

    private RuntimeResponse health(RuntimeRequest request, String correlationId) {
        if (!request.method().equals("GET")) {
            return new RuntimeResponse(405, common(correlationId, Map.of("Allow", "GET")), Optional.of(ProblemDetails.CONTENT_TYPE),
                    ProblemDetails.of(ProblemKind.METHOD_NOT_ALLOWED, "").withContext("", "", correlationId).toJson());
        }
        return new RuntimeResponse(200, common(correlationId, Map.of()), Optional.of(HttpBinding.JSON), HEALTH_BODY);
    }

    private RuntimeResponse notAllowed(Set<HttpMethod> allowed, Trace trace, String correlationId) {
        String allow = allowed.stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
        return problem(ProblemDetails.of(ProblemKind.METHOD_NOT_ALLOWED, ""), trace, correlationId, Map.of("Allow", allow));
    }

    /** The methods of the routes that match a path, for a method that the gateway does not serve. */
    private static Set<HttpMethod> served(TenantRuntime tenant, String rest) {
        Set<HttpMethod> found = EnumSet.noneOf(HttpMethod.class);
        for (HttpMethod candidate : HttpMethod.values()) {
            if (tenant.routes().match(candidate, rest) instanceof RouteMatch.Found) {
                found.add(candidate);
            }
        }
        return found;
    }

    private RuntimeResponse invalid(List<ValueProblem> problems, Trace trace, String correlationId) {
        problems.forEach(p -> trace.problems.add(p.code()));
        return problem(ProblemDetails.forRequestProblems(problems), trace, correlationId, Map.of());
    }

    private RuntimeResponse kind(ProblemKind kind, Trace trace, String correlationId) {
        return problem(ProblemDetails.of(kind, ""), trace, correlationId, Map.of());
    }

    private RuntimeResponse problem(ProblemDetails details, Trace trace, String correlationId, Map<String, String> extra) {
        if (trace.problems.isEmpty()) {
            String type = details.type();
            trace.problems.add(type.substring(type.lastIndexOf('/') + 1));
        }
        ProblemDetails withContext = details.withContext(trace.tenant == null ? "" : trace.tenant,
                trace.environment == null ? "" : trace.environment, correlationId);
        return new RuntimeResponse(details.status(), common(correlationId, extra), Optional.of(ProblemDetails.CONTENT_TYPE), withContext.toJson());
    }

    private static Map<String, String> common(String correlationId, Map<String, String> extra) {
        Map<String, String> headers = new LinkedHashMap<>(extra);
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Cache-Control", "no-store");
        headers.put(CORRELATION_HEADER, correlationId);
        return headers;
    }

    private static Optional<HttpMethod> parse(String method) {
        for (HttpMethod candidate : HttpMethod.values()) {
            if (candidate.name().equals(method)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static String method(String method) {
        return parse(method).map(Enum::name).orElse("OTHER");
    }

    /** The id of the request: the one the caller sent if it has the right form, otherwise a new one. */
    static String correlationId(Map<String, List<String>> headers) {
        Set<String> candidates = new TreeSet<>();
        headers.forEach((name, values) -> {
            if (name.equalsIgnoreCase(CORRELATION_HEADER)) {
                candidates.addAll(values);
            }
        });
        if (candidates.size() == 1) {
            String value = candidates.iterator().next();
            if (CORRELATION.matcher(value).matches()) {
                return value;
            }
        }
        return UUID.randomUUID().toString();
    }
}
