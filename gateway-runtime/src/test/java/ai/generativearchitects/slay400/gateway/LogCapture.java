package ai.generativearchitects.slay400.gateway;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures what a logger of the gateway writes through {@code System.Logger}, which goes to {@code java.util.logging} by
 * default. Close it to stop capturing.
 */
public final class LogCapture implements AutoCloseable {

    /** What was logged: level, message, and the class of the exception, if any. */
    public record Line(Level level, String message, String thrown) {
    }

    private final Logger logger;
    private final Level previous;
    private final List<Line> lines = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            lines.add(new Line(record.getLevel(), record.getMessage(), record.getThrown() == null ? null : record.getThrown().getClass().getName()));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    /** Starts capturing the logger with this name, at every level. */
    public LogCapture(String name) {
        logger = Logger.getLogger(name);
        previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    /** What was logged so far. */
    public List<Line> lines() {
        return List.copyOf(lines);
    }

    /** Everything logged so far, messages and exception classes, as one text. */
    public String text() {
        StringBuilder out = new StringBuilder();
        lines.forEach(line -> out.append(line.level()).append(' ').append(line.message()).append(' ').append(line.thrown()).append('\n'));
        return out.toString();
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setLevel(previous);
    }
}
