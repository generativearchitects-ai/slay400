package ai.generativearchitects.slay400.gateway.load;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The view of the file system through which the gateway finds and reads its bundle files, at start and at every poll
 * (kickoff 07). Tests replace it to control what a poll sees; the gateway uses {@link #system()}.
 */
public interface BundleFiles {

    /** The extension of a bundle file. */
    String EXTENSION = ".rpgapi";

    /**
     * The size and time of last change of a regular file.
     *
     * @param size     the size in bytes
     * @param modified the time of last change
     */
    record Stamp(long size, Instant modified) {

        /** @throws NullPointerException if {@code modified} is {@code null} */
        public Stamp {
            Objects.requireNonNull(modified, "modified");
        }
    }

    /** Whether there is a folder at the path. */
    boolean isFolder(Path path);

    /**
     * The regular files directly in a folder whose name ends in {@value #EXTENSION}, in any order; sub-folders are not read.
     * Empty when there is no folder at the path.
     *
     * @throws IOException if the folder exists and cannot be listed
     */
    List<Path> bundlesIn(Path folder) throws IOException;

    /**
     * The size and time of last change of the regular file at the path; empty when there is none.
     *
     * @throws IOException if the file exists and its attributes cannot be read
     */
    Optional<Stamp> stamp(Path file) throws IOException;

    /**
     * Copies the bytes of a file to a stream.
     *
     * @throws IOException if the file cannot be read
     */
    void copy(Path file, OutputStream to) throws IOException;

    /** The file system of the machine. */
    static BundleFiles system() {
        return SystemBundleFiles.INSTANCE;
    }
}
