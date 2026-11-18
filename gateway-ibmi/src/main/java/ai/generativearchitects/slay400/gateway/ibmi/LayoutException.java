package ai.generativearchitects.slay400.gateway.ibmi;

import java.util.Objects;

/** A value that does not fit its layout, or bytes that are not a value of it. The message names the field, never a value. */
public final class LayoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String path;

    /**
     * @param path   the field, as in PCML
     * @param reason what is wrong, without the value
     */
    public LayoutException(String path, String reason) {
        super(Objects.requireNonNull(path, "path") + ": " + Objects.requireNonNull(reason, "reason"), null, false, false);
        this.path = path;
    }

    /** The field. */
    public String path() {
        return path;
    }

    static LayoutException wrongKind(String path, String expected) {
        return new LayoutException(path, expected + " was expected");
    }
}
