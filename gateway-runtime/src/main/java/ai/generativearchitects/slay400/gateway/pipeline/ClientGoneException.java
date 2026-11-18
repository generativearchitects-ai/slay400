package ai.generativearchitects.slay400.gateway.pipeline;

/**
 * Thrown by the body supplier of a {@link RuntimeRequest} when the client went away, or stopped sending, while its body was
 * read. Anyone can cause it, so the pipeline logs it as one line at a low level, without a stack trace; this exception carries
 * none.
 */
public final class ClientGoneException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** A new exception, without message, cause or stack trace. */
    public ClientGoneException() {
        super(null, null, false, false);
    }
}
