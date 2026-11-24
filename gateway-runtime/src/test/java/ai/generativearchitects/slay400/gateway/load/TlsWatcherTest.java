package ai.generativearchitects.slay400.gateway.load;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.LogCapture;
import ai.generativearchitects.slay400.gateway.TestCertificates;
import ai.generativearchitects.slay400.gateway.config.TlsSettings;
import ai.generativearchitects.slay400.gateway.tls.TlsMaterial;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rotation of the certificate and key, without a server: what the watcher hands on, and what it logs (kickoff 08). */
class TlsWatcherTest {

    @TempDir
    Path dir;

    private long tick = Instant.parse("2026-11-01T00:00:00Z").getEpochSecond();
    private final List<TlsMaterial> taken = new CopyOnWriteArrayList<>();
    private final AtomicReference<IOException> failing = new AtomicReference<>();
    private TlsSettings settings;

    /** The machine's file system, failing while {@link #failing} holds an exception. */
    private final BundleFiles files = new BundleFiles() {
        private final BundleFiles system = BundleFiles.system();

        @Override
        public boolean isFolder(Path path) {
            return system.isFolder(path);
        }

        @Override
        public List<Path> bundlesIn(Path folder) throws IOException {
            return system.bundlesIn(folder);
        }

        @Override
        public Optional<Stamp> stamp(Path file) throws IOException {
            IOException failure = failing.get();
            if (failure != null) {
                throw failure;
            }
            return system.stamp(file);
        }

        @Override
        public void copy(Path file, OutputStream to) throws IOException {
            system.copy(file, to);
        }
    };

    @BeforeEach
    void paths() {
        settings = new TlsSettings(dir.resolve("tls.crt"), dir.resolve("tls.key"), Optional.empty());
    }

    private void install(TestCertificates.Pair certificate, TestCertificates.Pair key) throws IOException {
        Files.copy(certificate.certificateFile(), settings.certificate(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(key.keyFile(), settings.key(), StandardCopyOption.REPLACE_EXISTING);
        touch();
    }

    private void touch() throws IOException {
        Files.setLastModifiedTime(settings.certificate(), FileTime.from(Instant.ofEpochSecond(++tick)));
        Files.setLastModifiedTime(settings.key(), FileTime.from(Instant.ofEpochSecond(++tick)));
    }

    private TlsWatcher watcher(TestCertificates.Pair first) throws IOException {
        install(first, first);
        TlsMaterial material = TlsMaterial.load(settings, Instant.now()).material().orElseThrow();
        TlsWatcher watcher = new TlsWatcher(settings, material, files, Instant::now, dir);
        watcher.onChange(taken::add);
        return watcher;
    }

    private static List<String> messages(LogCapture log, Level level) {
        return log.lines().stream().filter(l -> l.level() == level).map(LogCapture.Line::message).toList();
    }

    @Test
    void aNewPairIsTakenAtTheSecondPollAndHandedToTheListener() throws IOException {
        TestCertificates.Pair first = TestCertificates.rsa(dir, "first", 365);
        TestCertificates.Pair second = TestCertificates.ec(dir, "second", 365);
        TlsWatcher watcher = watcher(first);
        assertThat(watcher.current().certificateChain().get(0)).isEqualTo(first.certificate());
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            // Nothing changed: nothing logged, nothing handed on.
            watcher.poll();
            watcher.poll();
            assertThat(log.lines()).isEmpty();
            install(second, second);
            watcher.poll();
            assertThat(taken).isEmpty();
            watcher.poll();
            assertThat(taken).hasSize(1);
            assertThat(taken.get(0).certificateChain().get(0)).isEqualTo(second.certificate());
            assertThat(watcher.current()).isSameAs(taken.get(0));
            assertThat(messages(log, Level.INFO)).containsExactly(
                    "The TLS certificate of the API listener was replaced for new connections; it is valid until " + second.certificate().getNotAfter().toInstant());
        }
    }

    @Test
    void theSameContentWithNewTimesIsNoChange() throws IOException {
        TestCertificates.Pair first = TestCertificates.rsa(dir, "first", 365);
        TlsWatcher watcher = watcher(first);
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            touch();
            watcher.poll();
            watcher.poll();
            watcher.poll();
            assertThat(taken).isEmpty();
            assertThat(log.lines()).isEmpty();
        }
    }

    @Test
    void aPairThatCannotBeUsedIsRefusedOnceAndAPairBackToTheOneInUseIsNoChange() throws IOException {
        TestCertificates.Pair first = TestCertificates.rsa(dir, "first", 365);
        TestCertificates.Pair other = TestCertificates.rsa(dir, "other", 365);
        TlsWatcher watcher = watcher(first);
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            install(first, other);
            for (int i = 0; i < 4; i++) {
                watcher.poll();
            }
            List<String> warnings = messages(log, Level.WARNING);
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0)).startsWith("The TLS certificate and key changed but cannot be used; the listener keeps the pair in use.\n")
                    .contains("GWY023").doesNotContain(other.keyText().substring(0, 40));
            // The same refused content with new times is not reported again.
            touch();
            watcher.poll();
            watcher.poll();
            assertThat(messages(log, Level.WARNING)).hasSize(1);
            // Back to the pair in use: no change, nothing handed on.
            install(first, first);
            watcher.poll();
            watcher.poll();
            assertThat(taken).isEmpty();
            assertThat(log.lines()).hasSize(1);
            // A missing key is refused too.
            Files.delete(settings.key());
            watcher.poll();
            watcher.poll();
            assertThat(messages(log, Level.WARNING)).hasSize(2);
            assertThat(messages(log, Level.WARNING).get(1)).contains("GWY022").contains("tls.key does not exist");
        }
        assertThat(watcher.current().certificateChain().get(0)).isEqualTo(first.certificate());
    }

    @Test
    void aListenerThatDoesNotTakeThePairKeepsTheOneInUse() throws IOException {
        TestCertificates.Pair first = TestCertificates.rsa(dir, "first", 365);
        TestCertificates.Pair second = TestCertificates.rsa(dir, "second", 365);
        TlsWatcher watcher = watcher(first);
        watcher.onChange(material -> {
            throw new IllegalStateException("not now");
        });
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            install(second, second);
            watcher.poll();
            watcher.poll();
            watcher.poll();
            assertThat(messages(log, Level.WARNING)).containsExactly(
                    "The TLS certificate and key changed but the listener did not take them (IllegalStateException); it keeps the pair in use");
            assertThat(log.lines().get(0).thrown()).isEqualTo(IllegalStateException.class.getName());
        }
        assertThat(watcher.current().certificateChain().get(0)).isEqualTo(first.certificate());
    }

    @Test
    void aPairNearItsEndIsTakenWithAWarning() throws IOException {
        TestCertificates.Pair first = TestCertificates.rsa(dir, "first", 365);
        TestCertificates.Pair soon = TestCertificates.rsa(dir, "soon", 10);
        TlsWatcher watcher = watcher(first);
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            install(soon, soon);
            watcher.poll();
            watcher.poll();
            assertThat(taken).hasSize(1);
            assertThat(messages(log, Level.WARNING)).singleElement().asString().startsWith("GWY025 The certificate in tls.crt expires on ");
        }
    }

    @Test
    void aFailingPollIsReportedOnceAndTheRecoveryInOneLine() throws IOException {
        TestCertificates.Pair first = TestCertificates.rsa(dir, "first", 365);
        TlsWatcher watcher = watcher(first);
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            failing.set(new IOException("the volume went away"));
            watcher.poll();
            watcher.poll();
            failing.set(new java.nio.file.AccessDeniedException("tls"));
            watcher.poll();
            failing.set(null);
            watcher.poll();
            watcher.poll();
            assertThat(messages(log, Level.WARNING)).containsExactly(
                    "The TLS certificate and key files could not be polled (IOException); the pair in use stays. The polls go on; this is reported again only if the failure changes",
                    "The TLS certificate and key files could not be polled (AccessDeniedException); the pair in use stays. The polls go on; this is reported again only if the failure changes");
            assertThat(messages(log, Level.INFO)).containsExactly("The TLS certificate and key files can be polled again");
        }
        assertThat(taken).isEmpty();
    }
}
