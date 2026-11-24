package ai.generativearchitects.slay400.gateway.load;

import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The bundle files of every tenant at one moment, after folders are listed, with the size and time of last change of each.
 * Two listings are equal when they name the same files, in the same order, with the same stamps: that is what a poll compares
 * (kickoff 07, rule 1). The content decides only when the listings differ ({@link BundleSnapshot}).
 *
 * @param tenants the files of each tenant, by tenant name, in the order of the configuration
 */
record BundleListing(Map<String, List<Listed>> tenants) {

    /**
     * An entry of a tenant's {@code bundles}, as it was at start.
     *
     * @param path   the file or folder
     * @param folder whether it was a folder at start; it stays one for as long as the gateway runs
     * @param at     where the entry is written in the configuration
     */
    record Entry(Path path, boolean folder, SourceLocation at) {
    }

    /**
     * One bundle file of a tenant.
     *
     * @param path  the file
     * @param stamp its size and time of last change; empty when a file entry names no file
     * @param entry the entry of the configuration it comes from
     */
    record Listed(Path path, Optional<BundleFiles.Stamp> stamp, Entry entry) {
    }

    BundleListing {
        Map<String, List<Listed>> copy = new LinkedHashMap<>();
        tenants.forEach((tenant, files) -> copy.put(tenant, List.copyOf(files)));
        tenants = java.util.Collections.unmodifiableMap(copy);
    }

    /**
     * Lists the entries: a file entry stands for its file, present or not; a folder entry for the bundle files directly in it,
     * in name order, possibly none.
     *
     * @throws IOException if a folder or a file's attributes cannot be read
     */
    static BundleListing of(Map<String, List<Entry>> entries, BundleFiles files) throws IOException {
        Map<String, List<Listed>> tenants = new LinkedHashMap<>();
        for (Map.Entry<String, List<Entry>> tenant : entries.entrySet()) {
            List<Listed> listed = new ArrayList<>();
            for (Entry entry : tenant.getValue()) {
                if (entry.folder()) {
                    List<Path> found = new ArrayList<>(files.bundlesIn(entry.path()));
                    found.sort(Comparator.comparing(path -> path.getFileName().toString()));
                    for (Path path : found) {
                        // A file gone between the listing and its stamp is gone from the folder.
                        Optional<BundleFiles.Stamp> stamp = files.stamp(path);
                        if (stamp.isPresent()) {
                            listed.add(new Listed(path, stamp, entry));
                        }
                    }
                } else {
                    listed.add(new Listed(entry.path(), files.stamp(entry.path()), entry));
                }
            }
            tenants.put(Objects.requireNonNull(tenant.getKey()), listed);
        }
        return new BundleListing(tenants);
    }
}
