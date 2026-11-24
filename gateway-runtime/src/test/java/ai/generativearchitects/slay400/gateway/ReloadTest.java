package ai.generativearchitects.slay400.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.load.BundleFiles;
import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.Reloader;
import ai.generativearchitects.slay400.gateway.load.StartOptions;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import ai.generativearchitects.slay400.gateway.load.Started;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeRequest;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeResponse;
import ai.generativearchitects.slay400.gateway.pipeline.ServedSet;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.tasks.BundleRequest;
import ai.generativearchitects.slay400.tasks.TaskResult;
import ai.generativearchitects.slay400.tasks.Tasks;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loading bundles without restart (kickoff 07): folder entries, stillness, all or nothing, one switch, removal, a file
 * replaced while it is read, the poll thread. The reloader is polled directly: nothing sleeps.
 */
class ReloadTest {

    @TempDir
    Path dir;

    private final List<String> access = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private long tick = Instant.parse("2026-11-01T00:00:00Z").getEpochSecond();

    private static final String SHIPPED = "200 {\"status\":\"S\",\"deliveryDate\":\"2026-11-20\"}";
    private static final String HELD = "200 {\"status\":\"H\",\"deliveryDate\":\"2026-11-20\"}";

    // ---- bundles of the shop fixture, in variants

    /**
     * Builds a bundle from a copy of the {@code shop} repository.
     *
     * @param removed the APIs left out
     * @param edits   triples of a file of the repository, a text and its replacement
     */
    private Path build(String name, String version, List<String> removed, String... edits) {
        try {
            Path source = Files.createTempDirectory(dir, "src-" + name + "-");
            Path shop = Fixtures.root().resolve("shop");
            try (var files = Files.walk(shop)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    String relative = shop.relativize(file).toString();
                    if (relative.startsWith("target")) {
                        continue;
                    }
                    Path target = source.resolve(relative);
                    if (Files.isDirectory(file)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(file, target);
                    }
                }
            }
            for (String api : removed) {
                try (var files = Files.walk(source.resolve("apis/" + api))) {
                    files.sorted(Collections.reverseOrder()).forEach(path -> path.toFile().delete());
                }
            }
            for (int i = 0; i < edits.length; i += 3) {
                Path file = source.resolve(edits[i]);
                String text = Files.readString(file);
                assertThat(text).contains(edits[i + 1]);
                Files.writeString(file, text.replace(edits[i + 1], edits[i + 2]));
            }
            Path target = Files.createTempDirectory(dir, "built-").resolve(name + "-" + version + ".rpgapi");
            TaskResult result = new Tasks(dir).bundle(source, new BundleRequest(name, version, target, Optional.empty(), Optional.empty()));
            assertThat(result.hasError()).as(result.diagnostics().toString()).isFalse();
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The order status API only, its shipped order answering with the status given. */
    private Path orders(String version, String status) {
        return build("orders", version, List.of("ITEMQTY"), "apis/ORDSTATUS/mock/ordstatus.yaml", "P0STAT: S,", "P0STAT: " + status + ",");
    }

    /** The item quantity API only. */
    private Path items(String version) {
        return build("items", version, List.of("ORDSTATUS"));
    }

    /** Puts a bundle at a place, with a time of last change later than every earlier one. */
    private Path place(Path bundle, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(bundle, target, StandardCopyOption.REPLACE_EXISTING);
            touch(target);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void touch(Path file) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(++tick)));
    }

    private void write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
            touch(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- the gateway

    private static String config(String... entries) {
        return "tenants:\n  acme:\n    bundles: [" + String.join(", ", entries) + "]\n    environments:\n      test: { code: T0001, backend: mock }\n";
    }

    private StartResult start(String config, StartOptions options) {
        return GatewayLoader.load(Fixtures.write(dir, "gateway.yaml", config), name -> null, access::add, options.withEvents(events::add));
    }

    private Started started(String config) {
        StartResult result = start(config, StartOptions.mockOnly());
        assertThat(result.started()).as(result.diagnostics().toString()).isPresent();
        return result.started().get();
    }

    private static RuntimeResponse get(Started started, String path) {
        return started.gateway().handle(new RuntimeRequest("GET", path, Map.of(), Map.of(), Optional.empty(), () -> new byte[0]));
    }

    private static String status(Started started) {
        RuntimeResponse response = get(started, "/acme/test/orders/1001/status");
        return response.status() + " " + new String(response.body(), StandardCharsets.UTF_8);
    }

    private static int items(Started started) {
        return started.gateway().handle(new RuntimeRequest("POST", "/acme/test/items/A1/quantity", Map.of(), Map.of(), Optional.of("application/json"),
                () -> "{\"quantity\":1}".getBytes(StandardCharsets.UTF_8))).status();
    }

    private static List<String> bundles(Started started) {
        return started.gateway().served().bundles().stream().map(b -> b.name() + "@" + b.version()).toList();
    }

    /** Two polls: a change is loaded at the second, once it was the same at both. */
    private static void settle(Started started) {
        started.reloader().poll();
        started.reloader().poll();
    }

    private static List<String> warnings(LogCapture log) {
        return log.lines().stream().filter(line -> line.level() == Level.WARNING).map(LogCapture.Line::message).toList();
    }

    // ---- folder entries

    @Test
    void aFolderStandsForItsBundleFilesInNameOrderAndNothingElse() throws IOException {
        Path folder = dir.resolve("bundles");
        place(orders("1.0.0", "S"), folder.resolve("1-orders.rpgapi"));
        place(items("1.0.0"), folder.resolve("2-items.rpgapi"));
        // Ignored: another extension, a sub-folder, and a sub-folder whose name ends in .rpgapi.
        write(folder.resolve("notes.txt"), "not a bundle".getBytes(StandardCharsets.UTF_8));
        write(folder.resolve("old/3-other.rpgapi"), "not read".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(folder.resolve("0-folder.rpgapi"));
        Started started = started(config("./bundles"));
        assertThat(bundles(started)).containsExactly("orders@1.0.0", "items@1.0.0");
        assertThat(status(started)).isEqualTo(SHIPPED);
        assertThat(items(started)).isEqualTo(200);
        started.close();
    }

    @Test
    void anEmptyFolderAtStartIsAnError() throws IOException {
        Files.createDirectories(dir.resolve("bundles"));
        StartResult result = start(config("./bundles"), StartOptions.mockOnly());
        assertThat(result.started()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("GWY019");
        assertThat(result.diagnostics().get(0).message()).isEqualTo("The folder bundles has no bundle file (*.rpgapi)");
    }

    @Test
    void aFolderThatDoesNotExistAtStartIsAnErrorAsAMissingFileIs() {
        StartResult folder = start(config("./bundles"), StartOptions.mockOnly());
        StartResult file = start(config("./shop-1.0.0.rpgapi"), StartOptions.mockOnly());
        assertThat(folder.started()).isEmpty();
        assertThat(file.started()).isEmpty();
        assertThat(folder.diagnostics()).isNotEmpty();
        assertThat(folder.diagnostics()).extracting(Diagnostic::code).isEqualTo(file.diagnostics().stream().map(Diagnostic::code).toList());
    }

    @Test
    void onlyTheEmptyOneOfTwoFoldersIsAnErrorAtStart() throws IOException {
        place(orders("1.0.0", "S"), dir.resolve("full/orders.rpgapi"));
        Files.createDirectories(dir.resolve("empty"));
        StartResult result = start(config("./full", "./empty"), StartOptions.mockOnly());
        assertThat(result.diagnostics()).extracting(Diagnostic::message).containsExactly("The folder empty has no bundle file (*.rpgapi)");
        assertThat(result.diagnostics().get(0).location().line()).isEqualTo(3);
    }

    @Test
    void theDiagnosticsOfABundleNameItsFileNotThePrivateCopy() {
        Path broken = dir.resolve("bundles/broken.rpgapi");
        write(broken, "this is not a zip".getBytes(StandardCharsets.UTF_8));
        StartResult result = start(config("./bundles"), StartOptions.mockOnly());
        assertThat(result.started()).isEmpty();
        assertThat(result.diagnostics()).isNotEmpty();
        assertThat(result.diagnostics()).allSatisfy(d -> {
            assertThat(d.location().file()).doesNotContain("slay400-bundles-");
            assertThat(d.message()).doesNotContain("slay400-bundles-");
        });
        // As the reader names the file it is given: by its name.
        assertThat(result.diagnostics()).extracting(d -> d.location().file()).contains("broken.rpgapi");
    }

    @Test
    void aFileThatChangesWhileItIsCopiedAtStartStopsTheStart() throws IOException {
        Path file = place(orders("1.0.0", "S"), dir.resolve("bundles/orders.rpgapi"));
        ReplacingFiles files = new ReplacingFiles(file, Files.readAllBytes(orders("2.0.0", "H")));
        files.armed = true;
        StartResult result = start(config("./bundles"), StartOptions.mockOnly().withFiles(files));
        assertThat(result.started()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("GWY021");
        assertThat(result.diagnostics().get(0).message()).isEqualTo("A bundle file changed while it was read; start again when it is complete");
    }

    @Test
    void aFolderThatCannotBeListedAtStartStopsTheStart() throws IOException {
        Files.createDirectories(dir.resolve("bundles"));
        BundleFiles system = BundleFiles.system();
        BundleFiles unreadable = new BundleFiles() {
            @Override
            public boolean isFolder(Path path) {
                return system.isFolder(path);
            }

            @Override
            public List<Path> bundlesIn(Path folder) throws IOException {
                throw new java.nio.file.AccessDeniedException(folder.toString());
            }

            @Override
            public Optional<Stamp> stamp(Path path) throws IOException {
                return system.stamp(path);
            }

            @Override
            public void copy(Path path, OutputStream to) throws IOException {
                system.copy(path, to);
            }
        };
        StartResult result = start(config("./bundles"), StartOptions.mockOnly().withFiles(unreadable));
        assertThat(result.started()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("GWY019");
        assertThat(result.diagnostics().get(0).message()).isEqualTo("A bundle folder or file cannot be read (AccessDeniedException)");
    }

    /** The largest bundle file the gateway copies: 132 MiB. */
    private static final long BOUND = 132L * 1024 * 1024;

    /** A file of {@code size} bytes that takes no room on disk where the file system allows it. */
    private Path sparse(Path file, long size) throws IOException {
        Files.createDirectories(file.getParent());
        try (java.io.RandomAccessFile out = new java.io.RandomAccessFile(file.toFile(), "rw")) {
            out.setLength(size);
        }
        touch(file);
        return file;
    }

    @Test
    void aBundleFileOverTheBoundStopsTheStartWithoutBeingCopied() throws IOException {
        place(orders("1.0.0", "S"), dir.resolve("bundles/a-orders.rpgapi"));
        sparse(dir.resolve("bundles/b-huge.rpgapi"), BOUND + 1);
        StartResult result = start(config("./bundles"), StartOptions.mockOnly());
        assertThat(result.started()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("GWY021");
        assertThat(result.diagnostics().get(0).message()).isEqualTo("The bundle file b-huge.rpgapi is larger than 138412032 bytes, more than any bundle can be; it is not read");
        assertThat(result.diagnostics().get(0).location().line()).isEqualTo(3);
    }

    @Test
    void aBundleFileOverTheBoundIsRefusedAtAReloadAndAFileAtTheBoundIsRead() throws IOException {
        Path folder = dir.resolve("bundles");
        place(orders("1.0.0", "S"), folder.resolve("a-orders.rpgapi"));
        Started started = started(config("./bundles"));
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            sparse(folder.resolve("b-huge.rpgapi"), BOUND + 1);
            settle(started);
            settle(started);
            assertThat(warnings(log)).hasSize(1);
            assertThat(warnings(log).get(0)).contains("GWY021").contains("b-huge.rpgapi is larger than 138412032 bytes");
            assertThat(status(started)).isEqualTo(SHIPPED);
            // At the bound the file is copied and read: here it is no zip, so the reader refuses it.
            sparse(folder.resolve("b-huge.rpgapi"), BOUND);
            settle(started);
            assertThat(warnings(log)).hasSize(2);
            assertThat(warnings(log).get(1)).doesNotContain("GWY021").contains("BDL");
        }
        assertThat(events).isEmpty();
        started.close();
    }

    @Test
    void filesAddedReplacedReplacedWithTheSameContentAndRemoved() throws IOException {
        Path folder = dir.resolve("bundles");
        // Version 2.0.0 changes the mock and the definition of the operation (its summary).
        Path ordersV2 = build("orders", "2.0.0", List.of("ITEMQTY"), "apis/ORDSTATUS/mock/ordstatus.yaml", "P0STAT: S,", "P0STAT: H,",
                "apis/ORDSTATUS/api.yaml", "summary: Returns the status of a purchase order", "summary: Returns the status of an order");
        // Version 3.0.0 has the definition of 2.0.0 and the mock data of 1.0.0.
        Path ordersV3 = build("orders", "3.0.0", List.of("ITEMQTY"),
                "apis/ORDSTATUS/api.yaml", "summary: Returns the status of a purchase order", "summary: Returns the status of an order");
        Path itemsV1 = items("1.0.0");
        place(orders("1.0.0", "S"), folder.resolve("orders.rpgapi"));
        Started started = started(config("./bundles"));
        assertThat(items(started)).isEqualTo(404);

        // Added.
        place(itemsV1, folder.resolve("items.rpgapi"));
        settle(started);
        assertThat(items(started)).isEqualTo(200);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).matches("\\{\"event\":\"reloaded\",\"bundles\":\\[\\{\"tenant\":\"acme\",\"name\":\"items\",\"version\":\"1.0.0\"},"
                + "\\{\"tenant\":\"acme\",\"name\":\"orders\",\"version\":\"1.0.0\"}],\"operations\":\\{\"added\":1,\"removed\":0,\"changed\":0},\"durationMs\":[0-9]+}");

        // Replaced with other content.
        place(ordersV2, folder.resolve("orders.rpgapi"));
        settle(started);
        assertThat(status(started)).isEqualTo(HELD);
        assertThat(access.get(access.size() - 1)).contains("\"bundle\":\"orders\",\"bundleVersion\":\"2.0.0\"");
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).contains("\"operations\":{\"added\":0,\"removed\":0,\"changed\":1}");
        // A new version that changes only the mock data changes no operation's definition.
        place(ordersV3, folder.resolve("orders.rpgapi"));
        settle(started);
        assertThat(status(started)).isEqualTo(SHIPPED);
        assertThat(events.get(2)).contains("\"version\":\"3.0.0\"").contains("\"operations\":{\"added\":0,\"removed\":0,\"changed\":0}");
        place(ordersV2, folder.resolve("orders.rpgapi"));
        settle(started);

        // Replaced with the same content: not a change.
        ServedSet before = started.gateway().served();
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            place(itemsV1, folder.resolve("items.rpgapi"));
            settle(started);
            settle(started);
            assertThat(log.lines()).isEmpty();
        }
        assertThat(started.gateway().served()).isSameAs(before);
        assertThat(events).hasSize(4);

        // Removed: its routes answer as unknown routes do.
        Files.delete(folder.resolve("items.rpgapi"));
        settle(started);
        assertThat(items(started)).isEqualTo(404);
        assertThat(events.get(4)).contains("\"operations\":{\"added\":0,\"removed\":1,\"changed\":0}");
        assertThat(status(started)).isEqualTo(HELD);
        started.close();
    }

    // ---- stillness

    @Test
    void aSetSeenOnceIsNotLoadedSeenTwiceItIs() {
        Path file = place(orders("1.0.0", "S"), dir.resolve("bundles/orders.rpgapi"));
        Started started = started(config("./bundles"));
        place(orders("2.0.0", "H"), file);
        started.reloader().poll();
        assertThat(status(started)).isEqualTo(SHIPPED);
        started.reloader().poll();
        assertThat(status(started)).isEqualTo(HELD);
        started.close();
    }

    @Test
    void aSetThatKeepsChangingIsNeverLoadedAndNothingIsLogged() {
        Path file = place(orders("1.0.0", "S"), dir.resolve("bundles/orders.rpgapi"));
        Path v2 = orders("2.0.0", "H");
        Path v3 = orders("3.0.0", "S");
        Started started = started(config("./bundles"));
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            for (int i = 0; i < 6; i++) {
                place(i % 2 == 0 ? v2 : v3, file);
                started.reloader().poll();
            }
            assertThat(log.lines()).isEmpty();
        }
        assertThat(bundles(started)).containsExactly("orders@1.0.0");
        assertThat(events).isEmpty();
        started.close();
    }

    // ---- all or nothing

    /** Starts on orders 1.0.0, puts {@code broken} beside it, and checks that the old set answers and one warning is logged. */
    private void refused(Path broken, String... codes) {
        Path folder = dir.resolve("bundles");
        place(orders("1.0.0", "S"), folder.resolve("a-orders.rpgapi"));
        Started started = started(config("./bundles"));
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            place(broken, folder.resolve("b-new.rpgapi"));
            assertThat(status(started)).isEqualTo(SHIPPED);
            settle(started);
            settle(started);
            assertThat(status(started)).isEqualTo(SHIPPED);
            assertThat(warnings(log)).hasSize(1);
            assertThat(warnings(log).get(0)).startsWith("The bundles changed but cannot be served; the running set stays.");
            for (String code : codes) {
                assertThat(warnings(log).get(0)).contains(code);
            }
            // The diagnostics name the bundle file, not its private copy.
            assertThat(warnings(log).get(0)).doesNotContain("slay400-bundles-");
        }
        assertThat(events).isEmpty();
        // A later good set is loaded.
        place(items("1.0.0"), folder.resolve("b-new.rpgapi"));
        settle(started);
        assertThat(bundles(started)).containsExactly("orders@1.0.0", "items@1.0.0");
        assertThat(items(started)).isEqualTo(200);
        started.close();
    }

    @Test
    void aBrokenBundleIsRefused() {
        Path broken = dir.resolve("broken.rpgapi");
        write(broken, "this is not a zip".getBytes(StandardCharsets.UTF_8));
        refused(broken);
    }

    @Test
    void aRouteConflictBetweenTwoBundlesIsRefused() {
        refused(build("orders-copy", "1.0.0", List.of("ITEMQTY")), "GWY013");
    }

    @Test
    void aBundleWhoseSchemaVersionIsTooNewIsRefused() throws IOException {
        Path tooNew = dir.resolve("too-new.rpgapi");
        rewriteManifest(items("1.0.0"), tooNew, manifest -> manifest.replaceFirst("\"bundleSchemaVersion\"\\s*:\\s*1", "\"bundleSchemaVersion\": 2"));
        refused(tooNew);
    }

    private static void rewriteManifest(Path from, Path to, java.util.function.UnaryOperator<String> change) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(from)); ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(to))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                byte[] bytes = in.readAllBytes();
                if (entry.getName().equals("manifest.json")) {
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    String changed = change.apply(text);
                    assertThat(changed).isNotEqualTo(text);
                    bytes = changed.getBytes(StandardCharsets.UTF_8);
                }
                out.putNextEntry(new ZipEntry(entry.getName()));
                out.write(bytes);
                out.closeEntry();
            }
        }
    }

    // ---- removal

    @Test
    void anEmptySetIsRefusedAndTheSameFilesBackAreNoChange() throws IOException {
        Path folder = dir.resolve("bundles");
        Path file = place(orders("1.0.0", "S"), folder.resolve("orders.rpgapi"));
        byte[] bytes = Files.readAllBytes(file);
        Started started = started(config("./bundles"));
        ServedSet before = started.gateway().served();
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            Files.delete(file);
            settle(started);
            settle(started);
            assertThat(warnings(log)).hasSize(1);
            assertThat(warnings(log).get(0)).contains("GWY020").contains("The tenant acme has no bundle file left");
            assertThat(status(started)).isEqualTo(SHIPPED);
            write(file, bytes);
            settle(started);
            assertThat(warnings(log)).hasSize(1);
        }
        assertThat(started.gateway().served()).isSameAs(before);
        assertThat(events).isEmpty();
        started.close();
    }

    @Test
    void aFolderThatEmptiesIsARemovalAFileEntryThatIsGoneRefusesTheSet() throws IOException {
        Path inFolder = place(items("1.0.0"), dir.resolve("bundles/items.rpgapi"));
        Path named = place(orders("1.0.0", "S"), dir.resolve("orders.rpgapi"));
        Started started = started(config("./bundles", "./orders.rpgapi"));
        assertThat(items(started)).isEqualTo(200);
        Files.delete(inFolder);
        settle(started);
        assertThat(items(started)).isEqualTo(404);
        assertThat(status(started)).isEqualTo(SHIPPED);
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            Files.delete(named);
            settle(started);
            assertThat(warnings(log)).hasSize(1);
            assertThat(status(started)).isEqualTo(SHIPPED);
        }
        started.close();
    }

    // ---- a file replaced while it is read

    /** The machine's file system, except that copying {@code target}, once armed, writes {@code replacement} over it halfway. */
    static final class ReplacingFiles implements BundleFiles {
        private final BundleFiles system = BundleFiles.system();
        private final Path target;
        private final byte[] replacement;
        final AtomicBoolean replaced = new AtomicBoolean();
        volatile boolean armed;

        ReplacingFiles(Path target, byte[] replacement) {
            this.target = target;
            this.replacement = replacement;
        }

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
            if (!file.equals(target) || !armed || replaced.getAndSet(true)) {
                system.copy(file, to);
                return;
            }
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            system.copy(file, all);
            byte[] first = all.toByteArray();
            int half = first.length / 2;
            to.write(first, 0, half);
            Files.write(file, replacement);
            Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-12-24T00:00:00Z")));
            to.write(replacement, Math.min(half, replacement.length), Math.max(0, replacement.length - half));
        }
    }

    @Test
    void aFileReplacedWhileItIsReadIsNotLoadedHalfOneVersionAndHalfAnother() throws IOException {
        Path file = place(orders("1.0.0", "S"), dir.resolve("bundles/orders.rpgapi"));
        byte[] v3 = Files.readAllBytes(orders("3.0.0", "H"));
        ReplacingFiles files = new ReplacingFiles(file, v3);
        Started started = start(config("./bundles"), StartOptions.mockOnly().withFiles(files)).started().orElseThrow();
        place(orders("2.0.0", "S"), file);
        files.armed = true;
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            settle(started);
            // The copy was torn: nothing loaded, nothing refused.
            assertThat(files.replaced).isTrue();
            assertThat(bundles(started)).containsExactly("orders@1.0.0");
            assertThat(warnings(log)).isEmpty();
            // The file is still now: two polls later its content is served, whole.
            settle(started);
            assertThat(bundles(started)).containsExactly("orders@3.0.0");
            assertThat(status(started)).isEqualTo(HELD);
            assertThat(warnings(log)).isEmpty();
        }
        started.close();
    }

    // ---- one switch, under load

    @Test
    void underLoadEveryAnswerIsThatOfOneSetAndNoneFails() throws Exception {
        Path file = place(orders("1.0.0", "S"), dir.resolve("bundles/orders.rpgapi"));
        Path v1 = orders("1.0.0", "S");
        Path v2 = orders("2.0.0", "H");
        Started started = started(config("./bundles"));
        int threads = 16;
        int switches = 20;
        // How many switches have completed; after switch k the set of version 1.0.0 is served when k is even.
        AtomicInteger switched = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger spanning = new AtomicInteger();
        List<String> failures = new CopyOnWriteArrayList<>();
        Map<String, String> answers = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int thread = t;
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; !stop.get(); i++) {
                        String id = "load-" + thread + "-" + i;
                        // Every eighth request is the slow order (300 ms), so that requests are in flight across switches.
                        boolean slow = i % 8 == 7;
                        int from = switched.get();
                        RuntimeResponse response = started.gateway().handle(new RuntimeRequest("GET", slow ? "/acme/test/orders/5/status" : "/acme/test/orders/1001/status",
                                Map.of(), Map.of("X-Correlation-Id", List.of(id)), Optional.empty(), () -> new byte[0]));
                        int to = switched.get();
                        requests.incrementAndGet();
                        if (to != from) {
                            spanning.incrementAndGet();
                        }
                        String body = new String(response.body(), StandardCharsets.UTF_8);
                        String version = body.contains("\"status\":\"S\"") ? "1.0.0" : body.contains("\"status\":\"H\"") ? "2.0.0" : body.contains("\"status\":\"P\"") ? "slow" : "?";
                        if (response.status() != 200 || version.equals("?")) {
                            failures.add(id + " " + response.status() + " " + body);
                        } else if (!version.equals("slow")) {
                            // The answer is that of a set served at some moment between the start and the end of the request; the
                            // set after switch to + 1 may already be served while the counter still reads to.
                            boolean possible = false;
                            for (int k = from; k <= to + 1; k++) {
                                possible |= version.equals(k % 2 == 0 ? "1.0.0" : "2.0.0");
                            }
                            if (!possible) {
                                failures.add(id + " answered " + version + " between switches " + from + " and " + to);
                            }
                            if (to == from) {
                                // No switch completed during the request: the answer is that of set from, or of the next one if it
                                // began just now; never of an older set.
                                String expected = from % 2 == 0 ? "1.0.0" : "2.0.0";
                                String next = (from + 1) % 2 == 0 ? "1.0.0" : "2.0.0";
                                if (!version.equals(expected) && !version.equals(next)) {
                                    failures.add(id + " answered an old set");
                                }
                            }
                            answers.put(id, version);
                        }
                    }
                } catch (RuntimeException e) {
                    failures.add("thread " + thread + ": " + e);
                } finally {
                    done.countDown();
                }
            });
        }
        for (int s = 1; s <= switches; s++) {
            place(s % 2 == 0 ? v1 : v2, file);
            settle(started);
            assertThat(bundles(started)).containsExactly("orders@" + (s % 2 == 0 ? "1.0.0" : "2.0.0"));
            switched.incrementAndGet();
            Thread.sleep(50);
        }
        stop.set(true);
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        // The access line of every answer names the version of the set that answered it.
        Pattern line = Pattern.compile("\"bundleVersion\":\"([^\"]+)\".*\"correlationId\":\"(load-[^\"]+)\"");
        Map<String, String> logged = new ConcurrentHashMap<>();
        for (String record : access) {
            Matcher m = line.matcher(record);
            if (m.find()) {
                logged.put(m.group(2), m.group(1));
            }
        }
        answers.forEach((id, version) -> {
            if (!version.equals(logged.get(id))) {
                failures.add(id + " answered " + version + " but its access line names " + logged.get(id));
            }
        });
        System.out.println("Under load: " + threads + " threads, " + requests.get() + " requests, " + spanning.get() + " of them across a switch, " + switches
                + " switches, " + failures.size() + " failures");
        assertThat(failures).isEmpty();
        assertThat(events).hasSize(switches);
        assertThat(requests.get()).isGreaterThan(switches * 4);
        assertThat(spanning.get()).isPositive();
        started.close();
    }

    // ---- the poll thread

    @Test
    void thePollThreadPollsAtTheIntervalAndShutdownStopsIt() throws Exception {
        Path file = place(orders("1.0.0", "S"), dir.resolve("bundles/orders.rpgapi"));
        Path v2 = orders("2.0.0", "H");
        CountDownLatch slept = new CountDownLatch(3);
        CountDownLatch blocked = new CountDownLatch(1);
        AtomicBoolean block = new AtomicBoolean();
        List<Duration> asked = new CopyOnWriteArrayList<>();
        StartOptions.Sleeper sleeper = duration -> {
            asked.add(duration);
            if (block.get()) {
                // A long wait, which only an interrupt ends.
                blocked.countDown();
                Thread.sleep(60_000);
            }
            slept.countDown();
            Thread.sleep(5);
        };
        StartResult result = start("reload:\n  intervalSeconds: 7\n" + config("./bundles"), StartOptions.mockOnly().withTime(sleeper, System::nanoTime));
        Started started = result.started().orElseThrow();
        assertThat(started.reloader().polling()).isFalse();
        started.startReloading();
        started.startReloading();
        assertThat(slept.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(started.reloader().polling()).isTrue();
        assertThat(Thread.getAllStackTraces().keySet()).filteredOn(t -> t.getName().equals(Reloader.THREAD_NAME)).hasSize(1).allMatch(Thread::isDaemon);
        // The thread polls: a new version is served without any call of poll() by the test.
        place(v2, file);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!bundles(started).equals(List.of("orders@2.0.0")) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(bundles(started)).containsExactly("orders@2.0.0");
        assertThat(asked).allMatch(d -> d.equals(Duration.ofSeconds(7)));
        // Shutdown interrupts the wait and the thread ends at once.
        block.set(true);
        assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
        long closing = System.nanoTime();
        started.close();
        assertThat(Duration.ofNanos(System.nanoTime() - closing)).isLessThan(Duration.ofSeconds(4));
        assertThat(started.reloader().polling()).isFalse();
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.getName().equals(Reloader.THREAD_NAME));
    }

    @Test
    void theSwitchLineSaysHowLongTheLoadTook() {
        Path file = place(orders("1.0.0", "S"), dir.resolve("bundles/orders.rpgapi"));
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
        // Every reading of the clock is 7.5 ms later than the one before.
        Started started = start(config("./bundles"), StartOptions.mockOnly().withTime(duration -> { }, () -> nanos.addAndGet(7_500_000))).started().orElseThrow();
        place(orders("2.0.0", "H"), file);
        settle(started);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).endsWith(",\"durationMs\":7}");
        started.close();
    }

    @Test
    void anIntervalOfZeroTurnsPollingOff() {
        place(orders("1.0.0", "S"), dir.resolve("bundles/orders.rpgapi"));
        Started started = start("reload:\n  intervalSeconds: 0\n" + config("./bundles"), StartOptions.mockOnly()).started().orElseThrow();
        started.startReloading();
        assertThat(started.reloader().polling()).isFalse();
        assertThat(started.reloader().interval()).isZero();
        started.close();
    }

    @Test
    void aFailingPollIsReportedOnceAgainWhenItChangesAndTheRecoveryInOneLine() throws IOException {
        Path file = place(orders("1.0.0", "S"), dir.resolve("bundles/orders.rpgapi"));
        java.util.concurrent.atomic.AtomicReference<IOException> failing = new java.util.concurrent.atomic.AtomicReference<>();
        BundleFiles system = BundleFiles.system();
        BundleFiles volume = new BundleFiles() {
            @Override
            public boolean isFolder(Path path) {
                return system.isFolder(path);
            }

            @Override
            public List<Path> bundlesIn(Path folder) throws IOException {
                IOException failure = failing.get();
                if (failure != null) {
                    throw failure;
                }
                return system.bundlesIn(folder);
            }

            @Override
            public Optional<Stamp> stamp(Path path) throws IOException {
                return system.stamp(path);
            }

            @Override
            public void copy(Path path, OutputStream to) throws IOException {
                system.copy(path, to);
            }
        };
        Started started = start(config("./bundles"), StartOptions.mockOnly().withFiles(volume)).started().orElseThrow();
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            // A volume that went away: one warning, with its exception, however many polls fail.
            failing.set(new IOException("the volume went away"));
            for (int i = 0; i < 5; i++) {
                started.reloader().poll();
            }
            assertThat(log.lines()).hasSize(1);
            assertThat(log.lines().get(0).level()).isEqualTo(Level.WARNING);
            assertThat(log.lines().get(0).message()).isEqualTo("The bundle files could not be polled (IOException); the running set stays. "
                    + "The polls go on; this is reported again only if the failure changes");
            assertThat(log.lines().get(0).thrown()).isEqualTo(IOException.class.getName());
            // Another failure is reported, once.
            failing.set(new java.nio.file.AccessDeniedException("bundles"));
            started.reloader().poll();
            started.reloader().poll();
            assertThat(log.lines()).hasSize(2);
            assertThat(log.lines().get(1).message()).contains("(AccessDeniedException)");
            // The recovery is one line, and the next poll that succeeds logs nothing.
            failing.set(null);
            started.reloader().poll();
            started.reloader().poll();
            assertThat(log.lines()).hasSize(3);
            assertThat(log.lines().get(2).level()).isEqualTo(Level.INFO);
            assertThat(log.lines().get(2).message()).isEqualTo("The bundle files can be polled again");
            // After a poll that succeeded, the same failure as before is reported again.
            failing.set(new IOException("the volume went away"));
            started.reloader().poll();
            assertThat(log.lines()).hasSize(4);
            assertThat(log.lines().get(3).level()).isEqualTo(Level.WARNING);
        }
        // Polling still works: a new version is served once the volume is back.
        failing.set(null);
        place(orders("2.0.0", "H"), file);
        settle(started);
        assertThat(status(started)).isEqualTo(HELD);
        started.close();
    }

    // ---- as before

    @Test
    void aConfigurationWithFileEntriesOnlyAndNoReloadSectionBehavesAsBefore() {
        place(orders("1.0.0", "S"), dir.resolve("orders.rpgapi"));
        Started started = started(config("./orders.rpgapi"));
        assertThat(started.reloader().interval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(status(started)).isEqualTo(SHIPPED);
        settle(started);
        assertThat(events).isEmpty();
        assertThat(started.startLine()).isEqualTo("{\"event\":\"started\",\"address\":\"127.0.0.1\",\"port\":8080,\"tenants\":1,\"environments\":1,\"operations\":1}");
        started.close();
    }
}
