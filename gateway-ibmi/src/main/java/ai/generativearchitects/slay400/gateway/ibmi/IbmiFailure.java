package ai.generativearchitects.slay400.gateway.ibmi;

import java.util.Objects;

/**
 * The connection to the IBM i did not work. The message is a fixed text with, at most, the class name of the cause; it never
 * names the host, the user, the password or a library, and the cause is not kept, since its message may.
 */
public final class IbmiFailure extends Exception {

    private static final long serialVersionUID = 1L;

    /** What did not work. */
    public enum Kind {
        /** The host does not resolve or does not answer, or TLS could not be set up. */
        UNREACHABLE,
        /** The sign-on was refused: wrong user or password, disabled profile, expired password. */
        SIGN_ON_REFUSED,
        /** The library list could not be set. */
        LIBRARY_LIST,
        /** The connection broke, or the system answered something unexpected. */
        BROKEN
    }

    private final Kind kind;

    /**
     * @param kind   what did not work
     * @param detail a fixed text, never a value of the configuration
     */
    public IbmiFailure(Kind kind, String detail) {
        super(Objects.requireNonNull(detail, "detail"), null, false, false);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** What did not work. */
    public Kind kind() {
        return kind;
    }
}
