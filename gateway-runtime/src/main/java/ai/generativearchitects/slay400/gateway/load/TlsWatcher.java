package ai.generativearchitects.slay400.gateway.load;

import ai.generativearchitects.slay400.gateway.log.LogFields;

import ai.generativearchitects.slay400.gateway.config.TlsSettings;
import ai.generativearchitects.slay400.gateway.tls.TlsMaterial;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.report.Report;
import ai.generativearchitects.slay400.report.TextReport;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Rotation of the API listener's certificate and key without restart (kickoff 08). The two files are polled with the bundle
 * files, by the same thread and at the same interval, with the same rules: a change is taken only once it is the same at two
 * polls in a row; a pair with the same content as the one in use is no change; a pair that cannot be used is refused with one
 * warning, the pair in use stays, and the same refused pair is not reported again; a failing poll is reported once.
 *
 * <p>A pair that loads and matches is handed to the {@linkplain #onChange listener} the server registered, which gives it to
 * the listener for new connections. Connections already open keep the pair they began with.
 */
public final class TlsWatcher {

    private static final System.Logger LOG = System.getLogger("slay400.gateway");

    private final TlsSettings settings;
    private final BundleFiles files;
    private final Supplier<Instant> clock;
    private final Path configFolder;
    private volatile TlsMaterial current;
    private volatile Consumer<TlsMaterial> listener = material -> { };

    private List<Optional<BundleFiles.Stamp>> inUse;
    private String inUseContent;
    private List<Optional<BundleFiles.Stamp>> previous;
    private List<Optional<BundleFiles.Stamp>> refused;
    private String refusedContent;
    private String failure;

    TlsWatcher(TlsSettings settings, TlsMaterial current, BundleFiles files, Supplier<Instant> clock, Path configFolder) throws IOException {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.current = Objects.requireNonNull(current, "current");
        this.files = Objects.requireNonNull(files, "files");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.configFolder = configFolder;
        this.inUse = stamps();
        this.inUseContent = content();
        this.previous = inUse;
    }

    /** The certificate and key in use for new connections. */
    public TlsMaterial current() {
        return current;
    }

    /** Sets what receives a new pair once it is taken; the server gives it to its listener. */
    public void onChange(Consumer<TlsMaterial> listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Looks at the two files once. Never throws. */
    public synchronized void poll() {
        try {
            pollOnce();
        } catch (IOException | RuntimeException e) {
            String what = e.getClass().getName() + ": " + e.getMessage();
            if (!what.equals(failure)) {
                failure = what;
                LOG.log(System.Logger.Level.WARNING, "The TLS certificate and key files could not be polled (" + e.getClass().getSimpleName()
                        + "); the pair in use stays. The polls go on; this is reported again only if the failure changes", e);
            }
            return;
        }
        if (failure != null) {
            failure = null;
            LOG.log(System.Logger.Level.INFO, "The TLS certificate and key files can be polled again");
        }
    }

    private void pollOnce() throws IOException {
        List<Optional<BundleFiles.Stamp>> now = stamps();
        if (now.equals(inUse)) {
            previous = now;
            return;
        }
        if (!now.equals(previous)) {
            previous = now;
            return;
        }
        if (now.equals(refused)) {
            return;
        }
        String content = now.stream().allMatch(Optional::isPresent) ? content() : "-";
        if (content.equals(inUseContent)) {
            inUse = now;
            return;
        }
        if (content.equals(refusedContent)) {
            refused = now;
            return;
        }
        TlsMaterial.Result result = TlsMaterial.load(settings, clock.get());
        if (result.material().isEmpty()) {
            refused = now;
            refusedContent = content;
            LOG.log(System.Logger.Level.WARNING, "The TLS certificate and key changed but cannot be used; the listener keeps the pair in use.\n"
                    + TextReport.render(new Report("reload", configFolder, result.diagnostics())));
            return;
        }
        TlsMaterial next = result.material().get();
        try {
            listener.accept(next);
        } catch (RuntimeException e) {
            refused = now;
            refusedContent = content;
            LOG.log(System.Logger.Level.WARNING, "The TLS certificate and key changed but the listener did not take them (" + e.getClass().getSimpleName()
                    + "); it keeps the pair in use", e);
            return;
        }
        current = next;
        inUse = now;
        inUseContent = content;
        refused = null;
        refusedContent = null;
        LOG.log(System.Logger.Level.INFO, "The TLS certificate of the API listener was replaced for new connections; it is valid until "
                + next.notAfter());
        for (Diagnostic warning : result.diagnostics()) {
            if (warning.severity() == Severity.WARNING) {
                LOG.log(System.Logger.Level.WARNING, warning.message(), LogFields.none().code(warning.code()));
            }
        }
    }

    private List<Optional<BundleFiles.Stamp>> stamps() throws IOException {
        return List.of(files.stamp(settings.certificate()), files.stamp(settings.key()));
    }

    /**
     * The SHA-256 of the two files together: two pairs with the same content are the same pair. Each file is read at most up to
     * {@link TlsMaterial#MAX_FILE_BYTES}, the bound of {@link TlsMaterial} (review of PR #9): a larger one is not read further,
     * and its content is "too large", which no pair in use has, so that the pair is loaded and refused (GWY022).
     */
    private String content() throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : List.of(settings.certificate(), settings.key())) {
                MessageDigest one = MessageDigest.getInstance("SHA-256");
                try (Bounded out = new Bounded(one, TlsMaterial.MAX_FILE_BYTES)) {
                    files.copy(file, out);
                } catch (Bounded.TooLarge e) {
                    return "too-large";
                }
                digest.update(one.digest());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java", e);
        }
    }

    /** Hashes what is written, and stops at the bound. */
    private static final class Bounded extends OutputStream {
        private final MessageDigest digest;
        private final long max;
        private long written;

        /** Thrown when more bytes arrive than the bound allows. */
        static final class TooLarge extends IOException {
            TooLarge() {
                super("over the bound");
            }
        }

        Bounded(MessageDigest digest, long max) {
            this.digest = digest;
            this.max = max;
        }

        @Override
        public void write(int b) throws IOException {
            count(1);
            digest.update((byte) b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            count(len);
            digest.update(b, off, len);
        }

        private void count(int bytes) throws TooLarge {
            written += bytes;
            if (written > max) {
                throw new TooLarge();
            }
        }
    }
}
