package ai.generativearchitects.slay400.gateway.log;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Makes every record of Java logging one JSON line on standard error (kickoff 11, A2): the root logger gets one handler with the
 * {@link JsonLogFormatter} in place of the console handler of the JDK, which writes text in its own format. The gateway's own
 * loggers ({@link System.Logger} maps onto Java logging) and Helidon's go through it. Access lines and events stay on standard
 * output; they are not records of Java logging. Calling it again replaces the handler; it never adds a second one.
 */
public final class JsonLogging {

    private static final Object LOCK = new Object();
    private static Handler installed;

    private JsonLogging() {
    }

    /** Writes the records to standard error, from now on. */
    public static void install() {
        install(System.err);
    }

    /** Writes the records to this stream, from now on, at level {@code INFO} and above. */
    public static void install(PrintStream stream) {
        Objects.requireNonNull(stream, "stream");
        synchronized (LOCK) {
            Logger root = LogManager.getLogManager().getLogger("");
            for (Handler handler : root.getHandlers()) {
                root.removeHandler(handler);
            }
            Handler handler = new LineHandler(stream);
            handler.setLevel(Level.INFO);
            root.setLevel(Level.INFO);
            root.addHandler(handler);
            installed = handler;
        }
    }

    /** Whether the JSON handler is the one of the root logger now. */
    public static boolean isInstalled() {
        synchronized (LOCK) {
            Logger root = LogManager.getLogManager().getLogger("");
            for (Handler handler : root.getHandlers()) {
                if (handler == installed) {
                    return true;
                }
            }
            return false;
        }
    }

    /** One line per record, written whole and flushed, so that two threads never interleave inside a line. */
    private static final class LineHandler extends Handler {
        private final PrintStream stream;

        LineHandler(PrintStream stream) {
            this.stream = stream;
            setFormatter(new JsonLogFormatter());
            try {
                setEncoding(StandardCharsets.UTF_8.name());
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void publish(LogRecord record) {
            if (!isLoggable(record)) {
                return;
            }
            String line;
            try {
                line = getFormatter().format(record);
            } catch (RuntimeException e) {
                reportError("a log record could not be formatted", e, ErrorManager.FORMAT_FAILURE);
                return;
            }
            synchronized (stream) {
                stream.print(line);
                stream.flush();
            }
        }

        @Override
        public void flush() {
            stream.flush();
        }

        @Override
        public void close() {
            flush();
        }
    }
}
