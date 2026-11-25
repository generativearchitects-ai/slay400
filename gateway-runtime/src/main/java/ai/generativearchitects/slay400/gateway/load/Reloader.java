package ai.generativearchitects.slay400.gateway.load;

import ai.generativearchitects.slay400.gateway.call.BackendProvider;
import ai.generativearchitects.slay400.gateway.call.OpenBackend;
import ai.generativearchitects.slay400.gateway.config.Backend;
import ai.generativearchitects.slay400.gateway.config.GatewayConfig;
import ai.generativearchitects.slay400.gateway.pipeline.CompiledOperation;
import ai.generativearchitects.slay400.gateway.pipeline.Gateway;
import ai.generativearchitects.slay400.gateway.pipeline.ServedSet;
import ai.generativearchitects.slay400.http.ReadLimits;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.report.Report;
import ai.generativearchitects.slay400.report.TextReport;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads the bundles again while the gateway runs (kickoff 07, decision 129). Every interval it lists the bundle files; when
 * the set differs from the one served and was the same at the poll before, it reads every file once into a private copy,
 * compiles the set beside the running one with every check of a start and, if none fails, switches the gateway to it at once.
 * Otherwise the running set stays, and the refused set is reported once: the next attempt is when the files change again.
 *
 * <p>A set whose files have other stamps but the same content as the one served is not a change. Nothing is logged for a poll
 * that finds no change. The environments' open backends (connections, credentials) are kept; everything of the set is built
 * again: route tables, codecs, bindings, mocks and the layouts of the {@code ibmi} backend.
 *
 * <p>One daemon thread, {@value #THREAD_NAME}, polls the bundle files and, with TLS, the certificate and key ({@link TlsWatcher}); a poll that fails is logged once and the next one still runs. {@link #poll()}
 * may also be called directly, which is what the tests do.
 */
public final class Reloader implements AutoCloseable {

    /** The name of the polling thread. */
    public static final String THREAD_NAME = "slay400-reload";

    private static final System.Logger LOG = System.getLogger("slay400.gateway");

    private final GatewayConfig config;
    private final Map<String, List<BundleListing.Entry>> entries;
    private final Map<Backend, BackendProvider> providers;
    private final Map<String, OpenBackend> backends;
    private final Gateway gateway;
    private final ReadLimits limits;
    private final StartOptions options;
    private final Optional<TlsWatcher> tls;

    private BundleListing served;
    private Map<String, List<String>> servedContent;
    private BundleListing previous;
    private BundleListing refused;
    private Map<String, List<String>> refusedContent;
    /** The exception of the last poll, as class and message, while polls fail; {@code null} after a poll that succeeded. */
    private String failure;

    private final Object lifecycle = new Object();
    private Thread thread;
    private volatile boolean running;

    Reloader(GatewayConfig config, Map<String, List<BundleListing.Entry>> entries, Map<Backend, BackendProvider> providers, Map<String, OpenBackend> backends,
             Gateway gateway, ReadLimits limits, StartOptions options, BundleListing served, Map<String, List<String>> servedContent,
             Optional<TlsWatcher> tls) {
        this.tls = Objects.requireNonNull(tls, "tls");
        this.config = Objects.requireNonNull(config, "config");
        this.entries = new java.util.LinkedHashMap<>(entries);
        this.providers = Map.copyOf(providers);
        this.backends = Map.copyOf(backends);
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.options = Objects.requireNonNull(options, "options");
        this.served = Objects.requireNonNull(served, "served");
        this.servedContent = Map.copyOf(servedContent);
        this.previous = served;
    }

    /** The interval between two polls; zero when reloading is off. */
    public Duration interval() {
        return Duration.ofSeconds(config.reloadIntervalSeconds());
    }

    /**
     * Looks at the bundle files once and, if the rules say so, loads and serves the new set. Never throws: a failure is
     * logged once, with its exception, and again only when it changes or after a poll succeeded, which is logged in one line;
     * the next poll starts from the same state.
     */
    public synchronized void poll() {
        try {
            pollOnce();
        } catch (IOException | RuntimeException e) {
            // Reported once: again only when the failure changes, or after a poll that succeeded (review of PR #8).
            String what = e.getClass().getName() + ": " + e.getMessage();
            if (!what.equals(failure)) {
                failure = what;
                LOG.log(System.Logger.Level.WARNING, "The bundle files could not be polled (" + e.getClass().getSimpleName()
                        + "); the running set stays. The polls go on; this is reported again only if the failure changes", e);
            }
            return;
        }
        if (failure != null) {
            failure = null;
            LOG.log(System.Logger.Level.INFO, "The bundle files can be polled again");
        }
    }

    /** One poll of the thread: the bundle files, then the certificate and key of the API listener when it speaks TLS. Never throws. */
    public void pollAll() {
        poll();
        tls.ifPresent(TlsWatcher::poll);
    }

    private void pollOnce() throws IOException {
        BundleListing now = BundleListing.of(entries, options.files());
        if (now.equals(served)) {
            previous = now;
            return;
        }
        if (!now.equals(previous)) {
            // Changed since the last poll: wait until it is still.
            previous = now;
            return;
        }
        if (now.equals(refused)) {
            return;
        }
        long started = options.nanoTime().getAsLong();
        try (BundleSnapshot snapshot = BundleSnapshot.take(now, options.files())) {
            if (!snapshot.stable()) {
                // A file was written to while it was read: it must be still for two polls again.
                previous = null;
                return;
            }
            Map<String, List<String>> content = snapshot.content(now);
            if (content.equals(servedContent)) {
                served = now;
                return;
            }
            if (content.equals(refusedContent)) {
                refused = now;
                return;
            }
            GatewayLoader.Compiled compiled = GatewayLoader.compile(config, now, snapshot, providers, limits, true, options.unavailable());
            if (GatewayLoader.errors(compiled.diagnostics()) > 0) {
                refuse(now, content, compiled.diagnostics());
                return;
            }
            ServedSet next;
            try {
                next = GatewayLoader.assemble(compiled.drafts(), backends);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "The bundles changed but cannot be served (" + e.getClass().getSimpleName() + "); the running set stays", e);
                refused = now;
                refusedContent = content;
                return;
            }
            ServedSet before = gateway.served();
            gateway.serve(next);
            served = now;
            servedContent = content;
            refused = null;
            refusedContent = null;
            long millis = (options.nanoTime().getAsLong() - started) / 1_000_000;
            event(switchLine(before, next, millis));
        }
    }

    private void refuse(BundleListing now, Map<String, List<String>> content, List<Diagnostic> diagnostics) {
        refused = now;
        refusedContent = content;
        LOG.log(System.Logger.Level.WARNING, "The bundles changed but cannot be served; the running set stays.\n"
                + TextReport.render(new Report("reload", config.file().toAbsolutePath().getParent(), diagnostics)));
    }

    private void event(String line) {
        try {
            options.events().accept(line);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "The switch line could not be written", e);
        }
    }

    /**
     * The one line of a switch: the bundles now served with their versions, the operations added, removed and changed, and how
     * long the load took. An operation is the same when its tenant and id are; it changed when its definition did.
     */
    static String switchLine(ServedSet before, ServedSet after, long millis) {
        Map<String, ApiOperation> old = operations(before);
        Map<String, ApiOperation> now = operations(after);
        int added = 0;
        int changed = 0;
        for (Map.Entry<String, ApiOperation> entry : now.entrySet()) {
            ApiOperation earlier = old.get(entry.getKey());
            if (earlier == null) {
                added++;
            } else if (!earlier.equals(entry.getValue())) {
                changed++;
            }
        }
        long removed = old.keySet().stream().filter(key -> !now.containsKey(key)).count();
        StringBuilder out = new StringBuilder("{\"event\":\"reloaded\",\"bundles\":[");
        for (int i = 0; i < after.bundles().size(); i++) {
            ServedSet.Bundle bundle = after.bundles().get(i);
            out.append(i == 0 ? "" : ",").append("{\"tenant\":").append(quote(bundle.tenant())).append(",\"name\":").append(quote(bundle.name()))
                    .append(",\"version\":").append(quote(bundle.version())).append('}');
        }
        return out.append("],\"operations\":{\"added\":").append(added).append(",\"removed\":").append(removed).append(",\"changed\":").append(changed)
                .append("},\"durationMs\":").append(millis).append('}').toString();
    }

    private static Map<String, ApiOperation> operations(ServedSet set) {
        Map<String, ApiOperation> operations = new HashMap<>();
        set.tenants().forEach((tenant, runtime) -> {
            for (CompiledOperation compiled : runtime.operations().values()) {
                operations.put(tenant + "/" + compiled.operation().id(), compiled.operation());
            }
        });
        return operations;
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** Starts the polling thread, unless the interval is zero or it runs already. */
    public void start() {
        synchronized (lifecycle) {
            if (thread != null || interval().isZero()) {
                return;
            }
            running = true;
            thread = new Thread(this::run, THREAD_NAME);
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Whether the polling thread runs. */
    public boolean polling() {
        synchronized (lifecycle) {
            return thread != null && thread.isAlive();
        }
    }

    private void run() {
        while (running) {
            try {
                options.sleeper().sleep(interval());
            } catch (InterruptedException e) {
                return;
            }
            if (!running) {
                return;
            }
            try {
                pollAll();
            } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
                LOG.log(System.Logger.Level.ERROR, "A poll of the bundle files failed", e);
            }
        }
    }

    /** Stops the polling thread and waits for it, at most five seconds. Never throws. */
    @Override
    public void close() {
        Thread stopping;
        synchronized (lifecycle) {
            running = false;
            stopping = thread;
        }
        if (stopping == null) {
            return;
        }
        stopping.interrupt();
        try {
            stopping.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
