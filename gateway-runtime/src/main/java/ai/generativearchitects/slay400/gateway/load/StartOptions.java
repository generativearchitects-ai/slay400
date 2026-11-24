package ai.generativearchitects.slay400.gateway.load;

import ai.generativearchitects.slay400.gateway.call.BackendProvider;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * How the gateway is started.
 *
 * @param providers    the backends available in this distribution, other than the mock
 * @param connectCheck whether a backend checks at start that it can be reached
 * @param files        the view of the file system through which bundle files are found and read
 * @param events       receives the one JSON line of every switch to a new set of bundles (kickoff 07)
 * @param sleeper      waits between two polls of the bundle files
 * @param nanoTime     the clock that measures how long a reload took, in nanoseconds
 */
public record StartOptions(List<BackendProvider> providers, boolean connectCheck, BundleFiles files, Consumer<String> events, Sleeper sleeper,
                           LongSupplier nanoTime) {

    /** Waits between two polls; a test replaces it so that nothing really waits. */
    @FunctionalInterface
    public interface Sleeper {
        /** Waits for the duration. @throws InterruptedException when the wait is interrupted, which ends the polling */
        void sleep(Duration duration) throws InterruptedException;
    }

    /** @throws NullPointerException if an argument is {@code null} */
    public StartOptions {
        providers = List.copyOf(providers);
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(sleeper, "sleeper");
        Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** The backends and connect check given, the machine's file system and clock; switch lines are discarded. */
    public StartOptions(List<BackendProvider> providers, boolean connectCheck) {
        this(providers, connectCheck, BundleFiles.system(), line -> { }, Thread::sleep, System::nanoTime);
    }

    /** Only the mock backend; nothing to connect to. */
    public static StartOptions mockOnly() {
        return new StartOptions(List.of(), true);
    }

    /** These options with switch lines sent to {@code events}. */
    public StartOptions withEvents(Consumer<String> events) {
        return new StartOptions(providers, connectCheck, files, events, sleeper, nanoTime);
    }

    /** These options with another view of the file system. */
    public StartOptions withFiles(BundleFiles files) {
        return new StartOptions(providers, connectCheck, files, events, sleeper, nanoTime);
    }

    /** These options with another way to wait and another clock. */
    public StartOptions withTime(Sleeper sleeper, LongSupplier nanoTime) {
        return new StartOptions(providers, connectCheck, files, events, sleeper, nanoTime);
    }
}
