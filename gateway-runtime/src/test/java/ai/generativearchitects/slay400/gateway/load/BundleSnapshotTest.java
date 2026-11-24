package ai.generativearchitects.slay400.gateway.load;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The listing of bundle files and their private copies (kickoff 07, rules 1 and 8). */
class BundleSnapshotTest {

    @TempDir
    Path dir;

    private static final SourceLocation AT = new SourceLocation("gateway.yaml", 3, 5);

    /** The machine's file system, listing a folder in reverse name order. */
    private static BundleFiles reversed() {
        BundleFiles system = BundleFiles.system();
        return new BundleFiles() {
            @Override
            public boolean isFolder(Path path) {
                return system.isFolder(path);
            }

            @Override
            public List<Path> bundlesIn(Path folder) throws IOException {
                List<Path> found = new ArrayList<>(system.bundlesIn(folder));
                found.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
                return found;
            }

            @Override
            public Optional<Stamp> stamp(Path file) throws IOException {
                return system.stamp(file);
            }

            @Override
            public void copy(Path file, OutputStream to) throws IOException {
                system.copy(file, to);
            }
        };
    }

    private static String sha256(String text) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aFolderIsListedInNameOrderWhateverOrderTheFileSystemGives() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("bundles"));
        Files.writeString(folder.resolve("b.rpgapi"), "BB");
        Files.writeString(folder.resolve("a.rpgapi"), "A");
        Files.writeString(folder.resolve("c.rpgapi"), "CCC");
        BundleListing listing = BundleListing.of(Map.of("acme", List.of(new BundleListing.Entry(folder, true, AT))), reversed());
        assertThat(listing.tenants().get("acme")).extracting(listed -> listed.path().getFileName().toString()).containsExactly("a.rpgapi", "b.rpgapi", "c.rpgapi");
        assertThat(listing.tenants().get("acme").get(1).stamp().orElseThrow().size()).isEqualTo(2);
    }

    @Test
    void aFileEntryIsListedEvenWhenItIsMissing() throws IOException {
        Path missing = dir.resolve("missing.rpgapi");
        BundleListing listing = BundleListing.of(Map.of("acme", List.of(new BundleListing.Entry(missing, false, AT))), BundleFiles.system());
        assertThat(listing.tenants().get("acme")).singleElement().satisfies(listed -> {
            assertThat(listed.path()).isEqualTo(missing);
            assertThat(listed.stamp()).isEmpty();
        });
    }

    @Test
    void eachFileIsCopiedOnceIntoAPrivateFolderThatCloseDeletes() throws Exception {
        Path a = Files.writeString(dir.resolve("a.rpgapi"), "A");
        Path missing = dir.resolve("missing.rpgapi");
        // The same file named twice is copied once.
        BundleListing listing = BundleListing.of(Map.of("acme", List.of(new BundleListing.Entry(a, false, AT), new BundleListing.Entry(a, false, AT),
                new BundleListing.Entry(missing, false, AT))), BundleFiles.system());
        Path copy;
        try (BundleSnapshot snapshot = BundleSnapshot.take(listing, BundleFiles.system())) {
            assertThat(snapshot.stable()).isTrue();
            copy = snapshot.copyOf(a).orElseThrow();
            assertThat(copy).isNotEqualTo(a);
            assertThat(copy.getFileName()).isEqualTo(a.getFileName());
            assertThat(copy.getParent().getParent().getFileName().toString()).startsWith("slay400-bundles-");
            assertThat(Files.readString(copy, StandardCharsets.UTF_8)).isEqualTo("A");
            assertThat(snapshot.copyOf(missing)).isEmpty();
            try (var files = Files.walk(copy.getParent().getParent())) {
                assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);
            }
            // The content of a file is its SHA-256; a missing file has none.
            assertThat(snapshot.content(listing).get("acme")).containsExactly(a + "\u0000" + sha256("A"), a + "\u0000" + sha256("A"), missing + "\u0000-");
        }
        assertThat(copy).doesNotExist();
        assertThat(copy.getParent().getParent()).doesNotExist();
    }

    @Test
    void theBoundIsThatOfTheLargestBundleTheReaderAccepts() {
        assertThat(BundleSnapshot.MAX_FILE_BYTES).isEqualTo(132L * 1024 * 1024);
    }

    @Test
    void aFileWhoseListedSizeIsOverTheBoundIsNotCopied() throws Exception {
        Path small = Files.writeString(dir.resolve("small.rpgapi"), "AAAA");
        Path large = Files.writeString(dir.resolve("large.rpgapi"), "BBBBB");
        AtomicInteger copied = new AtomicInteger();
        BundleFiles system = BundleFiles.system();
        BundleFiles counting = new BundleFiles() {
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
                return system.stamp(file);
            }

            @Override
            public void copy(Path file, OutputStream to) throws IOException {
                copied.incrementAndGet();
                system.copy(file, to);
            }
        };
        BundleListing listing = BundleListing.of(Map.of("acme", List.of(new BundleListing.Entry(small, false, AT), new BundleListing.Entry(large, false, AT))),
                counting);
        try (BundleSnapshot snapshot = BundleSnapshot.take(listing, counting, 4)) {
            assertThat(snapshot.tooLarge(small)).isFalse();
            assertThat(snapshot.copyOf(small)).isPresent();
            assertThat(snapshot.tooLarge(large)).isTrue();
            assertThat(snapshot.copyOf(large)).isEmpty();
            // Only the small file was read at all.
            assertThat(copied).hasValue(1);
            assertThat(snapshot.content(listing).get("acme")).containsExactly(small + "\u0000" + sha256("AAAA"), large + "\u0000too-large");
            assertThat(snapshot.maxFileBytes()).isEqualTo(4);
        }
    }

    @Test
    void aFileThatGrowsOverTheBoundWhileItIsCopiedIsStoppedAndNotKept() throws Exception {
        Path first = Files.writeString(dir.resolve("first.rpgapi"), "A");
        Path growing = Files.writeString(dir.resolve("growing.rpgapi"), "AB");
        AtomicInteger written = new AtomicInteger();
        BundleFiles system = BundleFiles.system();
        // The listed size of growing.rpgapi is 2 bytes, but its copy goes on for a million, one byte at a time.
        BundleFiles endless = new BundleFiles() {
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
                return system.stamp(file);
            }

            @Override
            public void copy(Path file, OutputStream to) throws IOException {
                if (!file.equals(growing)) {
                    system.copy(file, to);
                    return;
                }
                for (int i = 0; i < 1_000_000; i++) {
                    to.write('X');
                    written.incrementAndGet();
                }
            }
        };
        BundleListing listing = BundleListing.of(Map.of("acme", List.of(new BundleListing.Entry(first, false, AT), new BundleListing.Entry(growing, false, AT))),
                endless);
        Path folder;
        try (BundleSnapshot snapshot = BundleSnapshot.take(listing, endless, 10)) {
            assertThat(snapshot.tooLarge(first)).isFalse();
            assertThat(snapshot.tooLarge(growing)).isTrue();
            assertThat(snapshot.copyOf(growing)).isEmpty();
            // The copy stopped at the bound, and nothing of it is kept.
            assertThat(written).hasValue(10);
            folder = snapshot.copyOf(first).orElseThrow().getParent().getParent();
            try (var files = Files.walk(folder)) {
                assertThat(files.filter(Files::isRegularFile).toList()).containsExactly(snapshot.copyOf(first).get());
            }
        }
        assertThat(folder).doesNotExist();
    }
}
