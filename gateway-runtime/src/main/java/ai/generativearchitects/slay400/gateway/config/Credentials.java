package ai.generativearchitects.slay400.gateway.config;

import java.util.Arrays;
import java.util.Objects;

/**
 * The user and password of an {@code ibmi} environment, resolved from environment variables at start. The values are never
 * logged and never part of a diagnostic: {@link #toString()} shows neither, and there is no {@code equals} that compares them.
 */
public final class Credentials {

    private final String user;
    private final char[] password;

    /**
     * @param user     the user profile
     * @param password the password; copied
     * @throws NullPointerException if an argument is {@code null}
     */
    public Credentials(String user, char[] password) {
        this.user = Objects.requireNonNull(user, "user");
        this.password = Objects.requireNonNull(password, "password").clone();
    }

    /** The user profile. Never log it. */
    public String user() {
        return user;
    }

    /** A copy of the password. Never log it; clear the copy when it is no longer needed. */
    public char[] password() {
        return password.clone();
    }

    /** Overwrites the password held here. */
    public void clear() {
        Arrays.fill(password, '\0');
    }

    /** A fixed text: neither the user nor the password. */
    @Override
    public String toString() {
        return "Credentials[redacted]";
    }
}
