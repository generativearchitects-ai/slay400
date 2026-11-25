package ai.generativearchitects.slay400.gateway.load;

import ai.generativearchitects.slay400.gateway.call.BackendProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import ai.generativearchitects.slay400.gateway.config.Backend;
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
 * @param unavailable  why a backend that is not among the providers is missing, for its diagnostic; by default the start says
 *                     that the backend is not in this distribution (GWY016)
 */
public record StartOptions(List<BackendProvider> providers, boolean connectCheck, BundleFiles files, Consumer<String> events, Sleeper sleeper,
                           LongSupplier nanoTime, Map<Backend, Unavailable> unavailable) {

    /**
     * Why a backend is not available, as the start reports it for an environment that uses it.
     *
     * @param code    the code of the diagnostic
     * @param message what follows "The backend ... of the environment ..." in it
     */
    public record Unavailable(String code, String message) {

        /** @throws NullPointerException if an argument is {@code null} */
        public Unavailable {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

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
        unavailable = Map.copyOf(unavailable);
    }

    /** The options of before kickoff 10: no reason given for a missing backend. */
    public StartOptions(List<BackendProvider> providers, boolean connectCheck, BundleFiles files, Consumer<String> events, Sleeper sleeper,
                        LongSupplier nanoTime) {
        this(providers, connectCheck, files, events, sleeper, nanoTime, Map.of());
    }

    /** The backends and connect check given, the machine's file system and clock; switch lines are discarded. */
    public StartOptions(List<BackendProvider> providers, boolean connectCheck) {
        this(providers, connectCheck, BundleFiles.system(), line -> { }, Thread::sleep, System::nanoTime);
    }

    /** Only the mock backend; nothing to connect to. */
    public static StartOptions mockOnly() {
        return new StartOptions(List.of(), true);
    }

    /** These options with the reasons why backends are missing. */
    public StartOptions withUnavailable(Map<Backend, Unavailable> unavailable) {
        return new StartOptions(providers, connectCheck, files, events, sleeper, nanoTime, unavailable);
    }

    /** These options with switch lines sent to {@code events}. */
    public StartOptions withEvents(Consumer<String> events) {
        return new StartOptions(providers, connectCheck, files, events, sleeper, nanoTime, unavailable);
    }

    /** These options with another view of the file system. */
    public StartOptions withFiles(BundleFiles files) {
        return new StartOptions(providers, connectCheck, files, events, sleeper, nanoTime, unavailable);
    }

    /** These options with another way to wait and another clock. */
    public StartOptions withTime(Sleeper sleeper, LongSupplier nanoTime) {
        return new StartOptions(providers, connectCheck, files, events, sleeper, nanoTime, unavailable);
    }
}
