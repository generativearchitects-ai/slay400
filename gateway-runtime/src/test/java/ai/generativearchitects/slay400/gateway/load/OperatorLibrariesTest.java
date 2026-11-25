package ai.generativearchitects.slay400.gateway.load;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.Fixtures;
import ai.generativearchitects.slay400.gateway.call.BackendProvider;
import ai.generativearchitects.slay400.gateway.config.Backend;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The folder for the operator's libraries and the JTOpen in it (gateway kickoff 10). */
class OperatorLibrariesTest {

    @TempDir
    Path dir;

    // ---- jars made by the test

    /** A jar with these entries, each with these bytes. */
    private static Path jar(Path file, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    private static byte[] properties(String version) {
        return ("groupId=net.sf.jt400\nartifactId=jt400\nversion=" + version + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** A JTOpen of the given version, as the scan sees it: the entry class and the Maven properties. */
    private static Path jt400(Path file, String version) throws IOException {
        return jar(file, Map.of(OperatorLibraries.ENTRY_CLASS, new byte[] {1}, "META-INF/maven/net.sf.jt400/jt400/pom.properties", properties(version)));
    }

    /** Compiles Java sources into a folder, against the test's class path. */
    private Path compile(String name, Map<String, String> sources) throws IOException {
        Path source = dir.resolve(name + "-src");
        Path classes = dir.resolve(name + "-classes");
        Files.createDirectories(classes);
        List<String> arguments = new ArrayList<>(List.of("-d", classes.toString(), "-cp", System.getProperty("java.class.path")));
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path file = source.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            arguments.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler.run(null, null, null, arguments.toArray(new String[0]))).isZero();
        return classes;
    }

    /** A jar of the classes of a folder, with more entries. */
    private static Path jarOf(Path classes, Path file, Map<String, byte[]> more) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>(more);
        try (var walk = Files.walk(classes)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(path)) {
                    entries.put(classes.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
                }
            }
        }
        return jar(file, entries);
    }

    /** A stub of JTOpen: the class com.ibm.as400.access.AS400, compiled, with the Maven properties of 21.0.7. */
    private Path stubJt400(Path file) throws IOException {
        Path classes = compile("stub", Map.of("com.ibm.as400.access.AS400", "package com.ibm.as400.access; public class AS400 { }"));
        return jarOf(classes, file, Map.of("META-INF/maven/net.sf.jt400/jt400/pom.properties", properties("21.0.7")));
    }

    /** A backend as lib/ibmi holds one: a provider of the ibmi backend that needs JTOpen's entry class to be created. */
    private Path backendJar(Path file) throws IOException {
        Path classes = compile("backend", Map.of("test.backend.TestBackend", """
                package test.backend;
                public final class TestBackend implements ai.generativearchitects.slay400.gateway.call.BackendProvider {
                    /** Fails without JTOpen, as the real backend does. */
                    private final Object entry = new com.ibm.as400.access.AS400();
                    public TestBackend() { }
                    @Override public ai.generativearchitects.slay400.gateway.config.Backend backend() {
                        return ai.generativearchitects.slay400.gateway.config.Backend.IBMI;
                    }
                    @Override public java.util.List<String> unsupported(ai.generativearchitects.slay400.pcml.api.ApiOperation operation) {
                        return java.util.List.of();
                    }
                    @Override public ai.generativearchitects.slay400.gateway.call.BackendStart start(String tenant,
                            ai.generativearchitects.slay400.gateway.config.EnvironmentConfig environment,
                            java.util.List<ai.generativearchitects.slay400.pcml.api.ApiOperation> operations, boolean connectCheck) {
                        throw new UnsupportedOperationException();
                    }
                    public ClassLoader entryLoader() { return entry.getClass().getClassLoader(); }
                }
                """, "com.ibm.as400.access.AS400", "package com.ibm.as400.access; public class AS400 { }"));
        // The stub of JTOpen is compiled with it but not packed with it: it comes from the folder, as in a distribution.
        Files.delete(classes.resolve("com/ibm/as400/access/AS400.class"));
        return jarOf(classes, file, Map.of("META-INF/services/" + BackendProvider.class.getName(), "test.backend.TestBackend\n".getBytes(StandardCharsets.UTF_8)));
    }

    // ---- the folder

    @Test
    void theFolderIsTheVariableThenLibExtOfTheDistributionThenNone() {
        Properties home = new Properties();
        home.setProperty(OperatorLibraries.HOME_PROPERTY, dir.toString());
        assertThat(OperatorLibraries.folder(name -> name.equals("SLAY400_GATEWAY_EXT") ? "/opt/slay400/ext" : null, home)).contains(Path.of("/opt/slay400/ext"));
        assertThat(OperatorLibraries.folder(name -> null, home)).contains(dir.resolve("lib/ext").toAbsolutePath().normalize());
        assertThat(OperatorLibraries.folder(name -> "  ", home)).contains(dir.resolve("lib/ext").toAbsolutePath().normalize());
        assertThat(OperatorLibraries.folder(name -> null, new Properties())).isEmpty();
        Properties blank = new Properties();
        blank.setProperty(OperatorLibraries.HOME_PROPERTY, " ");
        assertThat(OperatorLibraries.folder(name -> null, blank)).isEmpty();
        assertThat(OperatorLibraries.FOLDER_VARIABLE).isEqualTo("SLAY400_GATEWAY_EXT");
    }

    // ---- the scan

    @Test
    void aFolderThatDoesNotExistOrIsEmptyOrHoldsItsReadmeHoldsNothing() throws IOException {
        Path missing = dir.resolve("missing");
        assertThat(OperatorLibraries.scan(missing)).isEqualTo(new OperatorLibraries.Found(Optional.of(missing), Optional.empty(), List.of()));
        Path ext = Files.createDirectories(dir.resolve("ext"));
        assertThat(OperatorLibraries.scan(ext).diagnostics()).isEmpty();
        Files.writeString(ext.resolve("README"), "Put the JTOpen jar here.");
        OperatorLibraries.Found found = OperatorLibraries.scan(ext);
        assertThat(found.jt400()).isEmpty();
        assertThat(found.diagnostics()).isEmpty();
        assertThat(found.startFields()).isEqualTo(",\"ext\":\"" + ext + "\",\"jt400\":\"none\"");
    }

    @Test
    void theVerifiedVersionIsFoundWithoutAWord() throws IOException {
        Path ext = dir.resolve("ext");
        Path jar = jt400(ext.resolve("jt400-21.0.7.jar"), "21.0.7");
        OperatorLibraries.Found found = OperatorLibraries.scan(ext);
        assertThat(found.jt400()).contains(new OperatorLibraries.Jar(jar, "21.0.7"));
        assertThat(found.diagnostics()).isEmpty();
        assertThat(found.startFields()).endsWith(",\"jt400\":\"21.0.7\"");
        assertThat(OperatorLibraries.VERIFIED_JT400).containsExactly("21.0.7");
    }

    @Test
    void jtopenIsKnownByItsEntryClassWhateverItsNameAndAnotherVersionIsAWarning() throws IOException {
        Path ext = dir.resolve("ext");
        jt400(ext.resolve("renamed.jar"), "20.0.0");
        OperatorLibraries.Found found = OperatorLibraries.scan(ext);
        assertThat(found.jt400()).map(OperatorLibraries.Jar::version).contains("20.0.0");
        assertThat(found.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo("GWY028");
            assertThat(d.severity()).isEqualTo(Severity.WARNING);
            assertThat(d.message()).isEqualTo("JTOpen 20.0.0 (renamed.jar) has not been verified on a real system; the verified versions are 21.0.7");
        });
    }

    @Test
    void withoutMavenPropertiesTheVersionComesFromTheNameOrIsUnknown() throws IOException {
        Path named = jar(dir.resolve("a/jt400-21.0.7.jar"), Map.of(OperatorLibraries.ENTRY_CLASS, new byte[] {1}));
        assertThat(OperatorLibraries.jt400(named)).contains(new OperatorLibraries.Jar(named, "21.0.7"));
        Path unnamed = jar(dir.resolve("b/library.jar"), Map.of(OperatorLibraries.ENTRY_CLASS, new byte[] {1}));
        assertThat(OperatorLibraries.jt400(unnamed)).contains(new OperatorLibraries.Jar(unnamed, "unknown"));
        Path blank = jar(dir.resolve("c/jt400-1.jar"), Map.of(OperatorLibraries.ENTRY_CLASS, new byte[] {1}, "META-INF/maven/net.sf.jt400/jt400/pom.properties",
                "version= \n".getBytes(StandardCharsets.UTF_8)));
        assertThat(OperatorLibraries.jt400(blank)).contains(new OperatorLibraries.Jar(blank, "1"));
    }

    @Test
    void filesThatAreNotJtopenAreNotLoadedAndNamedInOneWarning() throws IOException {
        Path ext = dir.resolve("ext");
        jt400(ext.resolve("jt400-21.0.7.jar"), "21.0.7");
        jar(ext.resolve("other.jar"), Map.of("org/example/Other.class", new byte[] {1}));
        Files.writeString(ext.resolve("notes.txt"), "not a jar");
        Files.writeString(ext.resolve("README"), "quiet");
        // A sub-folder is not looked into, even with JTOpen inside.
        jt400(ext.resolve("old/jt400-20.0.0.jar"), "20.0.0");
        OperatorLibraries.Found found = OperatorLibraries.scan(ext);
        assertThat(found.jt400()).map(OperatorLibraries.Jar::version).contains("21.0.7");
        assertThat(found.diagnostics()).extracting(Diagnostic::code, Diagnostic::message).containsExactly(
                Tuple.tuple("GWY029", "The folder " + ext + " holds files that are not JTOpen; they are not loaded: notes.txt, other.jar"));
        assertThat(found.diagnostics().get(0).severity()).isEqualTo(Severity.WARNING);
        assertThat(found.diagnostics().get(0).location().file()).isEqualTo(ext.toString());
    }

    @Test
    void twoJtopenJarsAreRefused() throws IOException {
        Path ext = dir.resolve("ext");
        jt400(ext.resolve("jt400-20.0.0.jar"), "20.0.0");
        jt400(ext.resolve("jt400-21.0.7.jar"), "21.0.7");
        OperatorLibraries.Found found = OperatorLibraries.scan(ext);
        assertThat(found.jt400()).isEmpty();
        assertThat(found.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo("GWY027");
            assertThat(d.severity()).isEqualTo(Severity.ERROR);
            assertThat(d.message()).isEqualTo("The folder " + ext + " holds more than one JTOpen jar (jt400-20.0.0.jar 20.0.0, jt400-21.0.7.jar 21.0.7); keep one");
        });
    }

    // ---- loading

    @Test
    void theBackendIsLoadedWithTheJtopenOfTheFolderInALoaderOfItsOwn() throws Exception {
        Path ext = dir.resolve("ext");
        stubJt400(ext.resolve("jt400-21.0.7.jar"));
        Path backend = backendJar(dir.resolve("lib/ibmi/gateway-ibmi.jar"));
        OperatorLibraries.Found found = OperatorLibraries.scan(ext);
        OperatorLibraries.Loaded loaded = OperatorLibraries.providers(found, List.of(backend), getClass().getClassLoader());
        assertThat(loaded.problem()).isEmpty();
        assertThat(loaded.providers()).hasSize(1);
        BackendProvider provider = loaded.providers().get(0);
        assertThat(provider.backend()).isEqualTo(Backend.IBMI);
        ClassLoader loader = provider.getClass().getClassLoader();
        assertThat(loader).isInstanceOf(URLClassLoader.class).isNotSameAs(getClass().getClassLoader());
        assertThat(loader.getName()).isEqualTo("slay400-backends");
        // JTOpen comes from the folder, through the same loader; the gateway's own classes from the parent.
        assertThat(provider.getClass().getMethod("entryLoader").invoke(provider)).isSameAs(loader);
        assertThat(BackendProvider.class.getClassLoader()).isSameAs(getClass().getClassLoader());
        assertThat(OperatorLibraries.unavailable(found, loaded)).isEmpty();
    }

    @Test
    void withoutJtopenTheBackendIsNotLoadedAndTheIbmiBackendIsUnavailableWithItsFolder() throws IOException {
        Path backend = backendJar(dir.resolve("lib/ibmi/gateway-ibmi.jar"));
        Path ext = Files.createDirectories(dir.resolve("ext"));
        OperatorLibraries.Found found = OperatorLibraries.scan(ext);
        OperatorLibraries.Loaded loaded = OperatorLibraries.providers(found, List.of(backend), getClass().getClassLoader());
        assertThat(loaded.providers()).isEmpty();
        assertThat(OperatorLibraries.unavailable(found, loaded)).containsExactly(Map.entry(Backend.IBMI, new StartOptions.Unavailable("GWY026",
                "needs JTOpen, which is not part of the gateway: put jt400-21.0.7.jar into the folder " + ext + " (see " + OperatorLibraries.DOCUMENTATION + ")")));
        Path missing = dir.resolve("nowhere");
        assertThat(OperatorLibraries.unavailable(OperatorLibraries.scan(missing), loaded).get(Backend.IBMI).message())
                .contains("into the folder " + missing + ", which does not exist");
    }

    @Test
    void aJtopenJarWhoseClassesCannotBeLoadedIsReportedNotThrown() throws IOException {
        Path ext = dir.resolve("ext");
        // The scan's idea of JTOpen, with an entry class that is not a class: creating the backend fails with a LinkageError.
        jt400(ext.resolve("jt400-21.0.7.jar"), "21.0.7");
        Path backend = backendJar(dir.resolve("lib/ibmi/gateway-ibmi.jar"));
        OperatorLibraries.Found found = OperatorLibraries.scan(ext);
        OperatorLibraries.Loaded loaded = OperatorLibraries.providers(found, List.of(backend), getClass().getClassLoader());
        assertThat(loaded.providers()).isEmpty();
        assertThat(loaded.problem()).contains("cannot be loaded with jt400-21.0.7.jar (ClassFormatError)");
        assertThat(OperatorLibraries.unavailable(found, loaded).get(Backend.IBMI)).isEqualTo(new StartOptions.Unavailable("GWY026",
                "cannot be loaded with jt400-21.0.7.jar (ClassFormatError), the JTOpen jar in " + ext + "; it may be incomplete (see "
                        + OperatorLibraries.DOCUMENTATION + ")"));
    }

    @Test
    void outsideADistributionTheClassPathDecides() {
        OperatorLibraries.Found none = new OperatorLibraries.Found(Optional.empty(), Optional.empty(), List.of());
        // The runtime's test class path has no JTOpen: no backend, and no GWY026 (the start says GWY016 as before).
        OperatorLibraries.Loaded loaded = OperatorLibraries.providers(none, List.of(), getClass().getClassLoader());
        assertThat(loaded.providers()).isEmpty();
        assertThat(loaded.problem()).isEmpty();
        assertThat(OperatorLibraries.unavailable(none, loaded)).isEmpty();
        assertThat(none.startFields()).isEqualTo(",\"ext\":null,\"jt400\":\"none\"");
    }

    // ---- the start

    @Test
    void anIbmiEnvironmentWithoutJtopenStopsTheStartWithGwy026() {
        Fixtures.bundle(dir, "shop");
        Path config = Fixtures.write(dir, "gateway.yaml", """
                tenants:
                  acme:
                    bundles: [./shop-1.0.0.rpgapi]
                    environments:
                      prod: { code: P0001, backend: ibmi, host: "${H}", libraryList: [APPLIB], credentials: { user: "${U}", password: "${P}" } }
                """);
        Map<String, String> variables = Map.of("H", "h.invalid", "U", "u", "P", "p");
        StartOptions options = StartOptions.mockOnly().withUnavailable(Map.of(Backend.IBMI, new StartOptions.Unavailable("GWY026", "needs JTOpen, put it there")));
        StartResult result = GatewayLoader.load(config, variables::get, line -> { }, options);
        assertThat(result.started()).isEmpty();
        assertThat(result.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo("GWY026");
            assertThat(d.message()).isEqualTo("The backend ibmi of the environment prod needs JTOpen, put it there");
            assertThat(d.location().line()).isEqualTo(5);
        });
        // Without a reason, as before.
        assertThat(GatewayLoader.load(config, variables::get, line -> { }, StartOptions.mockOnly()).diagnostics()).extracting(Diagnostic::code)
                .containsExactly("GWY016");
    }

    @Test
    void aMockOnlyConfigurationStartsWithoutJtopen() {
        Fixtures.bundle(dir, "shop");
        Path config = Fixtures.write(dir, "gateway.yaml", Fixtures.SHOP_CONFIG);
        StartOptions options = StartOptions.mockOnly().withUnavailable(Map.of(Backend.IBMI, new StartOptions.Unavailable("GWY026", "needs JTOpen")));
        StartResult result = GatewayLoader.load(config, name -> null, line -> { }, options);
        assertThat(result.diagnostics()).isEmpty();
        result.started().orElseThrow().close();
    }
}
