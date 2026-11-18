package ai.generativearchitects.slay400.gateway.ibmi;

import ai.generativearchitects.slay400.gateway.config.Credentials;
import ai.generativearchitects.slay400.gateway.config.IbmiSettings;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.SecureAS400;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The real system of the {@code ibmi} profile, from the four environment variables the owner sets as secrets of the GitHub
 * environment {@code ibmi}. The values are never printed: everything a test prints or writes goes through {@link #redact}.
 * The system is shared: one connection at a time, a handful of calls per run.
 */
final class IbmiSystem {

    static final String HOST = "IBMI_HOST";
    static final String USER = "IBMI_USER";
    static final String PASSWORD = "IBMI_PASSWORD";
    static final String LIBRARY = "IBMI_LIBRARY";

    /** The library name written in place of the owner's, as in the fixtures. */
    static final String LIBRARY_PLACEHOLDER = "OWNERLIB";

    private IbmiSystem() {
    }

    static String host() {
        return System.getenv(HOST);
    }

    static String user() {
        return System.getenv(USER);
    }

    static String library() {
        return System.getenv(LIBRARY).toUpperCase(Locale.ROOT);
    }

    /** The folder for the sources and the compiler's PCML, in the owner's home folder. */
    static String folder() {
        return "/home/" + user() + "/gateway-ibmi-test";
    }

    /** One signed-on connection with TLS and certificate verification (JT400's default). */
    static AS400 connect() throws Exception {
        char[] password = System.getenv(PASSWORD).toCharArray();
        AS400 system = new SecureAS400(host(), user(), password);
        java.util.Arrays.fill(password, '\0');
        system.setGuiAvailable(false);
        system.connectService(AS400.COMMAND);
        return system;
    }

    /** The settings of an ibmi environment on this system, with its library list. */
    static IbmiSettings settings(List<String> libraryList, int callTimeoutSeconds) {
        return new IbmiSettings(host(), libraryList, new Credentials(user(), System.getenv(PASSWORD).toCharArray()), true, 1, callTimeoutSeconds);
    }

    /** The text with the host, the user, the password and the library replaced, whatever their case: for logs and reports. */
    static String redact(String text) {
        return redact(text, "<library>");
    }

    /** A PCML document with the same replacements, and the library written as {@value #LIBRARY_PLACEHOLDER}, as in the fixtures. */
    static String redactPcml(String pcml) {
        return redact(pcml, LIBRARY_PLACEHOLDER);
    }

    private static String redact(String text, String library) {
        String out = text;
        out = replace(out, System.getenv(PASSWORD), "<password>");
        out = replace(out, host(), "<host>");
        out = replace(out, library(), library);
        out = replace(out, user(), "<user>");
        return out;
    }

    private static String replace(String text, String value, String with) {
        if (value == null || value.isEmpty()) {
            return text;
        }
        return Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE).matcher(text).replaceAll(Matcher.quoteReplacement(with));
    }

    /** Where the tests of the profile write what the workflow keeps as artifacts. */
    static Path output() {
        return Path.of("target", "ibmi").toAbsolutePath();
    }

    /** The repository root, from the module folder. */
    static Path root() {
        return Path.of("..").toAbsolutePath().normalize();
    }
}
