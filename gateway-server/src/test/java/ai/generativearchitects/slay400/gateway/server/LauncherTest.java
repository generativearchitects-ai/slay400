package ai.generativearchitects.slay400.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launch scripts, {@code bin/slay400-gateway} and {@code bin/slay400-gateway.cmd} (gateway kickoff 10), as the rules of
 * the launcher of {@code slay400-core} ask: Java from {@code JAVA_HOME}, else from the {@code PATH}, version 21 or later or one
 * sentence and exit status 3; arguments untouched; the exit status returned; symbolic links followed; {@code JAVA_OPTS} and
 * {@code SLAY400_GATEWAY_OPTS} passed to the JVM. Each test runs the script of this operating system over a distribution of
 * its own, whose {@code Main} only echoes what it got.
 */
class LauncherTest {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

    /** The {@code Main} of the test distribution: echoes its arguments, home and options, and exits with {@code exit=N}. */
    private static final String ECHO = """
            package ai.generativearchitects.slay400.gateway.server;

            public final class Main {
                public static void main(String[] args) throws Exception {
                    java.io.PrintStream out = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
                    out.println("home=" + System.getProperty("slay400.gateway.home"));
                    out.println("opt=" + System.getProperty("test.opt") + "," + System.getProperty("test.opt2"));
                    int exit = 0;
                    for (String arg : args) {
                        out.println("[" + arg + "]");
                        if (arg.startsWith("exit=")) {
                            exit = Integer.parseInt(arg.substring(5));
                        }
                    }
                    System.exit(exit);
                }
            }
            """;

    @TempDir
    Path dir;

    private Path home;

    @BeforeEach
    void distribution() throws Exception {
        home = dir.resolve("dist");
        Path bin = Files.createDirectories(home.resolve("bin"));
        for (String script : List.of("slay400-gateway", "slay400-gateway.cmd")) {
            Path target = bin.resolve(script);
            Files.copy(Path.of("src/launcher/bin").resolve(script), target, StandardCopyOption.REPLACE_EXISTING);
            if (!WINDOWS) {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }
        Path source = dir.resolve("echo/ai/generativearchitects/slay400/gateway/server/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, ECHO);
        Path classes = Files.createDirectories(dir.resolve("echo-classes"));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString())).isZero();
        Path lib = Files.createDirectories(home.resolve("lib"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(lib.resolve("gateway.jar")))) {
            jar.putNextEntry(new JarEntry("ai/generativearchitects/slay400/gateway/server/Main.class"));
            jar.write(Files.readAllBytes(classes.resolve("ai/generativearchitects/slay400/gateway/server/Main.class")));
            jar.closeEntry();
        }
    }

    /** What a run of the launcher gave. */
    record Run(int status, List<String> out, String err) {
    }

    private Path script() {
        return home.resolve(WINDOWS ? "bin/slay400-gateway.cmd" : "bin/slay400-gateway");
    }

    private static String javaHome() {
        return System.getProperty("java.home");
    }

    /** Runs the launcher with these arguments and this environment (a value of null removes a variable). */
    private Run run(Path launcher, Map<String, String> environment, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        if (WINDOWS) {
            command.addAll(List.of("cmd.exe", "/d", "/c"));
        }
        command.add(launcher.toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> env = builder.environment();
        env.remove("JAVA_OPTS");
        env.remove("SLAY400_GATEWAY_OPTS");
        env.put("JAVA_HOME", javaHome());
        environment.forEach((name, value) -> {
            if (value == null) {
                env.remove(name);
            } else {
                env.put(name, value);
            }
        });
        Path err = Files.createTempFile(dir, "err", ".txt");
        builder.redirectError(err.toFile());
        Process process = builder.start();
        byte[] out = process.getInputStream().readAllBytes();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        String text = new String(out, StandardCharsets.UTF_8).replace("\r\n", "\n");
        return new Run(process.exitValue(), text.isEmpty() ? List.of() : List.of(text.split("\n")), Files.readString(err, StandardCharsets.UTF_8).trim());
    }

    private Run run(Map<String, String> environment, String... args) throws Exception {
        return run(script(), environment, args);
    }

    private static List<String> arguments(Run run) {
        return run.out().stream().filter(line -> line.startsWith("[")).toList();
    }

    // ---- arguments and exit status

    @Test
    void argumentsArriveUnchangedAndTheExitStatusIsTheGateways() throws Exception {
        Run run = run(Map.of(), "run", "--config", "a file with spaces.yaml", "it's", "naïve-ü-€", "exit=7");
        assertThat(run.status()).isEqualTo(7);
        assertThat(arguments(run)).containsExactly("[run]", "[--config]", "[a file with spaces.yaml]", "[it's]", "[naïve-ü-€]", "[exit=7]");
        assertThat(run.out().get(0)).isEqualTo("home=" + home.toRealPath());
        assertThat(run.err()).isEmpty();
    }

    @Test
    void quotesAndAnEmptyArgumentArriveUnchanged() throws Exception {
        // cmd.exe has no way to pass an empty argument or a lone double quote through %* that every caller agrees on; the
        // POSIX script passes both.
        assumeTrue(!WINDOWS, "POSIX shells only");
        Run run = run(Map.of(), "", "say \"hello\"", "$HOME", "*", "a\\b");
        assertThat(run.status()).isZero();
        assertThat(arguments(run)).containsExactly("[]", "[say \"hello\"]", "[$HOME]", "[*]", "[a\\b]");
    }

    @Test
    void theOptionsOfTheJvmComeFromJavaOptsAndSlay400GatewayOpts() throws Exception {
        Run run = run(Map.of("JAVA_OPTS", "-Dtest.opt=one", "SLAY400_GATEWAY_OPTS", "-Dtest.opt2=two"), "x");
        assertThat(run.status()).isZero();
        assertThat(run.out()).contains("opt=one,two");
    }

    // ---- symbolic links

    @Test
    void aSymbolicLinkOnThePathFindsTheDistribution() throws Exception {
        Path link = Files.createDirectories(dir.resolve("on-the-path")).resolve(script().getFileName());
        try {
            Files.createSymbolicLink(link, script());
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "no symbolic links here: " + e.getMessage());
        }
        Run run = run(link, Map.of(), "via-link");
        assertThat(run.status()).isZero();
        assertThat(run.out()).contains("home=" + home.toRealPath(), "[via-link]");
    }

    // ---- Java

    /** A folder with a fake {@code java} that reports this version, as {@code java -version} does, on standard error. */
    private Path fakeJava(String name, String versionLine) throws IOException {
        Path bin = Files.createDirectories(dir.resolve(name + "/bin"));
        Path java = bin.resolve("java");
        Files.writeString(java, "#!/bin/sh\necho '" + versionLine + "' >&2\nexit 0\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin.getParent();
    }

    /** A folder with links to the commands the POSIX script needs, and no java. */
    private Path toolsWithoutJava() throws IOException {
        Path tools = Files.createDirectories(dir.resolve("tools"));
        for (String tool : List.of("sh", "sed", "head", "cut", "dirname", "readlink")) {
            for (String folder : List.of("/bin", "/usr/bin")) {
                Path found = Path.of(folder, tool);
                if (Files.isExecutable(found) && !Files.exists(tools.resolve(tool))) {
                    Files.createSymbolicLink(tools.resolve(tool), found);
                }
            }
        }
        return tools;
    }

    /** A Java 17 the CI job installs for this test on Windows; empty elsewhere. */
    private static String java17() {
        String java17 = System.getenv("SLAY400_TEST_JAVA17");
        return java17 == null || java17.isBlank() ? null : java17;
    }

    @Test
    void withoutJavaTheLauncherSaysSoAndExitsWith3() throws Exception {
        Map<String, String> environment = new HashMap<>();
        environment.put("JAVA_HOME", null);
        environment.put("PATH", WINDOWS ? System.getenv("SystemRoot") + "\\System32" : toolsWithoutJava().toString());
        Run run = run(environment, "run");
        assertThat(run.status()).isEqualTo(3);
        assertThat(run.err()).isEqualTo("slay400-gateway needs Java 21 or later and found no Java: set JAVA_HOME or put java on the PATH.");
        assertThat(run.out()).isEmpty();
        // A JAVA_HOME without java in it is no Java either.
        Run empty = run(Map.of("JAVA_HOME", Files.createDirectories(dir.resolve("empty-jdk")).toString()), "run");
        assertThat(empty.status()).isEqualTo(3);
        assertThat(empty.err()).startsWith("slay400-gateway needs Java 21 or later");
    }

    @Test
    void anOlderJavaIsNamedAndExitsWith3() throws Exception {
        String java17;
        if (WINDOWS) {
            java17 = java17();
            assumeTrue(java17 != null, "SLAY400_TEST_JAVA17 names a Java 17 in CI");
        } else {
            java17 = fakeJava("java17", "openjdk version \"17.0.13\" 2024-10-15").toString();
            Path java8 = fakeJava("java8", "java version \"1.8.0_402\"");
            Run old = run(Map.of("JAVA_HOME", java8.toString()), "run");
            assertThat(old.status()).isEqualTo(3);
            assertThat(old.err()).isEqualTo("slay400-gateway needs Java 21 or later and found 1.8.0_402 at " + java8.resolve("bin/java") + ".");
        }
        Run run = run(Map.of("JAVA_HOME", java17), "run");
        assertThat(run.status()).isEqualTo(3);
        assertThat(run.err()).startsWith("slay400-gateway needs Java 21 or later and found 17.").contains(java17);
        assertThat(run.out()).isEmpty();
    }

    @Test
    void javaHomeWinsOverThePath() throws Exception {
        String older = WINDOWS ? java17() : fakeJava("java17", "openjdk version \"17.0.13\" 2024-10-15").toString();
        assumeTrue(older != null, "SLAY400_TEST_JAVA17 names a Java 17 in CI");
        String path = Path.of(older, "bin") + java.io.File.pathSeparator + System.getenv("PATH");
        Run run = run(Map.of("JAVA_HOME", javaHome(), "PATH", path), "ok");
        assertThat(run.status()).isZero();
        assertThat(arguments(run)).containsExactly("[ok]");
        // Without JAVA_HOME the older Java first on the PATH is the one found.
        Map<String, String> fromPath = new HashMap<>();
        fromPath.put("JAVA_HOME", null);
        fromPath.put("PATH", path);
        assertThat(run(fromPath, "ok").status()).isEqualTo(3);
    }
}
