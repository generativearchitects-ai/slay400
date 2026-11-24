package ai.generativearchitects.slay400.gateway.load;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link BundleFiles} on the file system of the machine. */
final class SystemBundleFiles implements BundleFiles {

    static final SystemBundleFiles INSTANCE = new SystemBundleFiles();

    private SystemBundleFiles() {
    }

    @Override
    public boolean isFolder(Path path) {
        return Files.isDirectory(path);
    }

    @Override
    public List<Path> bundlesIn(Path folder) throws IOException {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path path : stream) {
                if (path.getFileName().toString().endsWith(EXTENSION) && Files.isRegularFile(path)) {
                    found.add(path);
                }
            }
        } catch (NoSuchFileException | NotDirectoryException e) {
            return List.of();
        }
        return found;
    }

    @Override
    public Optional<Stamp> stamp(Path file) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        if (!attributes.isRegularFile()) {
            return Optional.empty();
        }
        return Optional.of(new Stamp(attributes.size(), attributes.lastModifiedTime().toInstant()));
    }

    @Override
    public void copy(Path file, OutputStream to) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(to);
        }
    }
}
