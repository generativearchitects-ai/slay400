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
 * reads a bundle from a path only, so the gateway reads the copy, which nobody else writes: a file replaced while it is copied
 * shows as a changed stamp, and the snapshot is then not {@link #stable()}. The copies are deleted on {@link #close()}.
 */
final class BundleSnapshot implements AutoCloseable {

    private final Path folder;
    private final Map<Path, Path> copies;
    private final Map<Path, String> hashes;
    private final boolean stable;

    private BundleSnapshot(Path folder, Map<Path, Path> copies, Map<Path, String> hashes, boolean stable) {
        this.folder = folder;
        this.copies = copies;
        this.hashes = hashes;
        this.stable = stable;
    }

    /**
     * Copies every file of the listing that exists into a new private folder.
     *
     * @throws IOException if a file cannot be read or the copy cannot be written; nothing is left behind
     */
    static BundleSnapshot take(BundleListing listing, BundleFiles files) throws IOException {
        Path folder = Files.createTempDirectory("slay400-bundles-");
        Map<Path, Path> copies = new HashMap<>();
        Map<Path, String> hashes = new HashMap<>();
        boolean stable = true;
        try {
            for (List<BundleListing.Listed> tenant : listing.tenants().values()) {
                for (BundleListing.Listed listed : tenant) {
                    if (listed.stamp().isEmpty() || copies.containsKey(listed.path())) {
                        continue;
                    }
                    Path copy = folder.resolve(copies.size() + BundleFiles.EXTENSION);
                    MessageDigest digest = sha256();
                    try (OutputStream out = new DigestOutputStream(Files.newOutputStream(copy), digest)) {
                        files.copy(listed.path(), out);
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
        return new BundleSnapshot(folder, copies, hashes, stable);
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
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(folder);
        } catch (IOException | UncheckedIOException e) {
            System.getLogger("slay400.gateway").log(System.Logger.Level.WARNING, "A private copy of the bundles could not be deleted: " + e.getClass().getSimpleName());
        }
    }
}
