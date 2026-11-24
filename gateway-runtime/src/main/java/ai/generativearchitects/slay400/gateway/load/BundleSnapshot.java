package ai.generativearchitects.slay400.gateway.load;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A private copy of every file of a listing, each read once, with the SHA-256 of its content (kickoff 07, rule 8). The core
 * reads a bundle from a path only, so the gateway reads the copy, which nobody else writes, under the file's own name in a
 * numbered folder of its own: a file replaced while it is copied
 * shows as a changed stamp, and the snapshot is then not {@link #stable()}. The copies are deleted on {@link #close()}.
 */
final class BundleSnapshot implements AutoCloseable {

    /**
     * The largest bundle file that is copied, in bytes: 132 MiB. The reader of the core accepts at most 128 MiB of entries in
     * all (and 4,096 entries); a zip file holding them is larger only by its headers, at most about 600 bytes per entry, and by
     * a fraction of a percent where an entry does not compress. A file over this bound cannot be a bundle the reader accepts,
     * so it is refused without being copied whole into the temporary folder (review of PR #8). The core does not export its
     * limits; the numbers are those of its {@code BundleFormat.Limits.STANDARD}.
     */
    static final long MAX_FILE_BYTES = 132L * 1024 * 1024;

    /** What the content of a file over the bound is, in place of its hash. */
    private static final String TOO_LARGE = "too-large";

    private final Path folder;
    private final Map<Path, Path> copies;
    private final Map<Path, String> hashes;
    private final boolean stable;
    private final long maxFileBytes;

    private BundleSnapshot(Path folder, Map<Path, Path> copies, Map<Path, String> hashes, boolean stable, long maxFileBytes) {
        this.folder = folder;
        this.copies = copies;
        this.hashes = hashes;
        this.stable = stable;
        this.maxFileBytes = maxFileBytes;
    }

    /** Thrown by the stream of a copy when more bytes arrive than the bound allows. */
    private static final class TooLarge extends IOException {
        TooLarge() {
            super("over the bound");
        }
    }

    /** Counts what is written, and stops at the bound. */
    private static final class Bounded extends java.io.FilterOutputStream {
        private final long max;
        private long written;

        Bounded(OutputStream out, long max) {
            super(out);
            this.max = max;
        }

        @Override
        public void write(int b) throws IOException {
            count(1);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            count(len);
            out.write(b, off, len);
        }

        private void count(int bytes) throws TooLarge {
            written += bytes;
            if (written > max) {
                throw new TooLarge();
            }
        }
    }

    /**
     * Copies every file of the listing that exists into a new private folder, each file at most {@link #MAX_FILE_BYTES}.
     *
     * @throws IOException if a file cannot be read or the copy cannot be written; nothing is left behind
     */
    static BundleSnapshot take(BundleListing listing, BundleFiles files) throws IOException {
        return take(listing, files, MAX_FILE_BYTES);
    }

    /**
     * Copies every file of the listing that exists into a new private folder. A file whose listed size is over the bound is
     * not copied; one that grows over it while it is copied is not copied further. Either is {@link #tooLarge(Path) too large}.
     *
     * @param maxFileBytes the largest file copied, in bytes
     * @throws IOException if a file cannot be read or the copy cannot be written; nothing is left behind
     */
    static BundleSnapshot take(BundleListing listing, BundleFiles files, long maxFileBytes) throws IOException {
        Path folder = Files.createTempDirectory("slay400-bundles-");
        Map<Path, Path> copies = new HashMap<>();
        Map<Path, String> hashes = new HashMap<>();
        boolean stable = true;
        try {
            for (List<BundleListing.Listed> tenant : listing.tenants().values()) {
                for (BundleListing.Listed listed : tenant) {
                    if (listed.stamp().isEmpty() || hashes.containsKey(listed.path())) {
                        continue;
                    }
                    if (listed.stamp().get().size() > maxFileBytes) {
                        hashes.put(listed.path(), TOO_LARGE);
                        continue;
                    }
                    // Under the file's own name, so that the reader's diagnostics name it as they name the original.
                    Path copy = Files.createDirectory(folder.resolve(String.valueOf(hashes.size()))).resolve(listed.path().getFileName());
                    MessageDigest digest = sha256();
                    try (OutputStream out = new Bounded(new DigestOutputStream(Files.newOutputStream(copy), digest), maxFileBytes)) {
                        files.copy(listed.path(), out);
                    } catch (TooLarge e) {
                        Files.deleteIfExists(copy);
                        hashes.put(listed.path(), TOO_LARGE);
                        continue;
                    }
                    copies.put(listed.path(), copy);
                    hashes.put(listed.path(), HexFormat.of().formatHex(digest.digest()));
                    if (!files.stamp(listed.path()).equals(listed.stamp())) {
                        stable = false;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            delete(folder);
            throw e;
        }
        return new BundleSnapshot(folder, copies, hashes, stable, maxFileBytes);
    }

    /** Whether a file of the listing is over the bound, and so was not copied. */
    boolean tooLarge(Path file) {
        return TOO_LARGE.equals(hashes.get(file));
    }

    /** The bound of this snapshot, in bytes. */
    long maxFileBytes() {
        return maxFileBytes;
    }

    /** Whether every file had the stamp of the listing after it was copied: none was written to while it was read. */
    boolean stable() {
        return stable;
    }

    /** The private copy of a file of the listing; empty for a file entry that names no file. */
    Optional<Path> copyOf(Path file) {
        return Optional.ofNullable(copies.get(file));
    }

    /**
     * What the listing holds by content: for each tenant, each file with the hash of its content, or with nothing when it is
     * missing. Two listings with the same content are the same set, whatever their stamps (kickoff 07, rule 1).
     */
    Map<String, List<String>> content(BundleListing listing) {
        Map<String, List<String>> content = new LinkedHashMap<>();
        listing.tenants().forEach((tenant, files) -> {
            List<String> entries = new ArrayList<>();
            for (BundleListing.Listed listed : files) {
                entries.add(listed.path() + "\u0000" + Optional.ofNullable(hashes.get(listed.path())).orElse("-"));
            }
            content.put(tenant, List.copyOf(entries));
        });
        return java.util.Collections.unmodifiableMap(content);
    }

    @Override
    public void close() {
        delete(folder);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java", e);
        }
    }

    private static void delete(Path folder) {
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(file);
            }
        } catch (IOException | UncheckedIOException e) {
            System.getLogger("slay400.gateway").log(System.Logger.Level.WARNING, "A private copy of the bundles could not be deleted: " + e.getClass().getSimpleName());
        }
    }
}
