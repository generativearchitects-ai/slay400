package ai.generativearchitects.slay400.gateway.load;

import ai.generativearchitects.slay400.gateway.call.BackendProvider;
import ai.generativearchitects.slay400.gateway.config.Backend;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The folder for operator-supplied libraries and the JTOpen (JT400) in it (decisions 143 and 144, gateway kickoff 10). JTOpen
 * is not part of any distribution of the gateway: the operator puts its jar into this folder, and the gateway loads it at
 * start.
 *
 * <p>How the classes are loaded: the {@code ibmi} backend ({@code lib/ibmi/} of the distribution) and the one JTOpen jar of the
 * folder get a class loader of their own, whose parent is the gateway's; the backend is found in it through
 * {@link ServiceLoader}. Only regular files directly in the folder are looked at, and only once, at start; nothing else of the
 * folder is loaded, and its path is printed in the start line. With no JTOpen, the {@code ibmi} backend is not available:
 * a configuration with {@code mock} environments only starts as before, one with an {@code ibmi} environment is refused
 * ({@code GWY026}).
 *
 * <p>Diagnostic codes: {@code GWY026} no JTOpen for an {@code ibmi} environment; {@code GWY027} more than one JTOpen jar in the
 * folder; {@code GWY028}, a warning, a version of JTOpen that has not been verified on a real system; {@code GWY029}, a
 * warning, files in the folder that are not JTOpen and are not loaded.
 */
public final class OperatorLibraries {

    /** The environment variable that names the folder; without it, {@code lib/ext} of the distribution. */
    public static final String FOLDER_VARIABLE = "SLAY400_GATEWAY_EXT";

    /** The system property the launcher sets to the folder of the distribution. */
    public static final String HOME_PROPERTY = "slay400.gateway.home";

    /** The versions of JTOpen verified on a real system (decision 121), in this one place. */
    public static final List<String> VERIFIED_JT400 = List.of("21.0.7");

    /** Where the operator reads how to get JTOpen. */
    public static final String DOCUMENTATION = "https://github.com/generativearchitects-ai/slay400#jtopen-for-the-ibmi-backend";

    /** The class every JTOpen jar holds; a jar with it is JTOpen. */
    static final String ENTRY_CLASS = "com/ibm/as400/access/AS400.class";

    private static final String POM_PROPERTIES = "META-INF/maven/net.sf.jt400/jt400/pom.properties";
    private static final Pattern FILE_VERSION = Pattern.compile("jt400-(.+)\\.jar");
    private static final List<String> QUIET = List.of("README", "README.md", "README.txt");

    private OperatorLibraries() {
    }

    /**
     * A JTOpen jar of the folder.
     *
     * @param file    the jar
     * @param version its version, from its Maven properties or its name; {@code unknown} when neither gives one
     */
    public record Jar(Path file, String version) {

        /** @throws NullPointerException if an argument is {@code null} */
        public Jar {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(version, "version");
        }
    }

    /**
     * What the folder holds.
     *
     * @param folder      the folder looked at; empty when the gateway runs outside a distribution, from its class path
     * @param jt400       the JTOpen jar; empty when there is none, or more than one
     * @param diagnostics GWY027 to GWY029
     */
    public record Found(Optional<Path> folder, Optional<Jar> jt400, List<Diagnostic> diagnostics) {

        /** @throws NullPointerException if an argument is {@code null} */
        public Found {
            Objects.requireNonNull(folder, "folder");
            Objects.requireNonNull(jt400, "jt400");
            diagnostics = List.copyOf(diagnostics);
        }

        /** The fields of the start line: the folder and the version of JTOpen, {@code none} without one. */
        public String startFields() {
            return ",\"ext\":" + folder.map(f -> json(f.toString())).orElse("null") + ",\"jt400\":" + json(jt400.map(Jar::version).orElse("none"));
        }
    }

    /**
     * The folder: the value of {@value #FOLDER_VARIABLE} when it is set, otherwise {@code lib/ext} of the distribution named
     * by the system property {@value #HOME_PROPERTY}; empty when neither is set (a gateway run from a class path).
     */
    public static Optional<Path> folder(Function<String, String> environment, Properties properties) {
        String set = environment.apply(FOLDER_VARIABLE);
        if (set != null && !set.isBlank()) {
            return Optional.of(Path.of(set).toAbsolutePath().normalize());
        }
        String home = properties.getProperty(HOME_PROPERTY);
        if (home != null && !home.isBlank()) {
            return Optional.of(Path.of(home, "lib", "ext").toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    /** Looks at the regular files directly in the folder, once. A folder that does not exist holds nothing. */
    public static Found scan(Path folder) {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(folder)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
                for (Path file : stream) {
                    if (Files.isRegularFile(file)) {
                        files.add(file);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        files.sort(Comparator.comparing(file -> file.getFileName().toString()));
        List<Jar> jars = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (QUIET.contains(name)) {
                continue;
            }
            Optional<Jar> jar = jt400(file);
            if (jar.isPresent()) {
                jars.add(jar.get());
            } else {
                ignored.add(name);
            }
        }
        List<Diagnostic> found = new ArrayList<>();
        SourceLocation at = new SourceLocation(folder.toString(), 1, 1);
        if (!ignored.isEmpty()) {
            found.add(new Diagnostic("GWY029", Severity.WARNING, "The folder " + folder + " holds files that are not JTOpen; they are not loaded: "
                    + String.join(", ", ignored), at));
        }
        if (jars.size() > 1) {
            found.add(new Diagnostic("GWY027", Severity.ERROR, "The folder " + folder + " holds more than one JTOpen jar ("
                    + String.join(", ", jars.stream().map(j -> j.file().getFileName() + " " + j.version()).toList()) + "); keep one", at));
            return new Found(Optional.of(folder), Optional.empty(), found);
        }
        if (jars.size() == 1 && !VERIFIED_JT400.contains(jars.get(0).version())) {
            found.add(new Diagnostic("GWY028", Severity.WARNING, "JTOpen " + jars.get(0).version() + " (" + jars.get(0).file().getFileName()
                    + ") has not been verified on a real system; the verified versions are " + String.join(", ", VERIFIED_JT400), at));
        }
        return new Found(Optional.of(folder), jars.stream().findFirst(), found);
    }

    /** The JTOpen jar this file is, if it is a jar that holds the entry class of JTOpen. */
    static Optional<Jar> jt400(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.getEntry(ENTRY_CLASS) == null) {
                return Optional.empty();
            }
            ZipEntry properties = zip.getEntry(POM_PROPERTIES);
            if (properties != null) {
                Properties read = new Properties();
                try (InputStream in = zip.getInputStream(properties)) {
                    read.load(in);
                }
                String version = read.getProperty("version");
                if (version != null && !version.isBlank()) {
                    return Optional.of(new Jar(file, version.trim()));
                }
            }
            Matcher name = FILE_VERSION.matcher(file.getFileName().toString());
            return Optional.of(new Jar(file, name.matches() ? name.group(1) : "unknown"));
        } catch (IOException e) {
            // Not a zip file: not JTOpen.
            return Optional.empty();
        }
    }

    /**
     * The backends loaded with the operator's libraries.
     *
     * @param providers the backends other than the mock that this gateway can open
     * @param problem   why the backends of {@code lib/ibmi} could not be loaded with the JTOpen of the folder, if they could not
     */
    public record Loaded(List<BackendProvider> providers, Optional<String> problem) {

        /** @throws NullPointerException if an argument is {@code null} */
        public Loaded {
            providers = List.copyOf(providers);
            Objects.requireNonNull(problem, "problem");
        }
    }

    /**
     * The backends other than the mock that this gateway can open. In a distribution, those of {@code lib/ibmi} loaded with the
     * JTOpen of the folder, none without one. Outside a distribution, those on the class path, if JTOpen is on it too.
     *
     * @param found       what the folder holds
     * @param backendJars the jars of the backends, {@code lib/ibmi/*.jar} of the distribution
     * @param parent      the class loader of the gateway
     */
    public static Loaded providers(Found found, List<Path> backendJars, ClassLoader parent) {
        if (found.folder().isEmpty()) {
            try {
                Class.forName(ENTRY_CLASS.replace('/', '.').replace(".class", ""), false, parent);
            } catch (ClassNotFoundException e) {
                return new Loaded(List.of(), Optional.empty());
            }
            return load(parent, "the class path");
        }
        if (found.jt400().isEmpty()) {
            return new Loaded(List.of(), Optional.empty());
        }
        List<URL> urls = new ArrayList<>();
        try {
            for (Path jar : backendJars) {
                urls.add(jar.toUri().toURL());
            }
            urls.add(found.jt400().get().file().toUri().toURL());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
        return load(new URLClassLoader("slay400-backends", urls.toArray(new URL[0]), parent), found.jt400().get().file().getFileName().toString());
    }

    /** The backends of the loader; none, with the reason, when a class they need is missing, as from an incomplete jar. */
    private static Loaded load(ClassLoader loader, String jt400) {
        List<BackendProvider> providers = new ArrayList<>();
        try {
            ServiceLoader.load(BackendProvider.class, loader).forEach(providers::add);
        } catch (ServiceConfigurationError | LinkageError e) {
            Throwable cause = e.getCause() instanceof LinkageError linkage ? linkage : e;
            return new Loaded(List.of(), Optional.of("cannot be loaded with " + jt400 + " (" + cause.getClass().getSimpleName() + ")"));
        }
        return new Loaded(providers, Optional.empty());
    }

    /**
     * Why the {@code ibmi} backend is not available, for the start: GWY026, with the folder and the documentation; empty when
     * it is available, or when the gateway runs outside a distribution (the start then says GWY016, as before).
     */
    public static Map<Backend, StartOptions.Unavailable> unavailable(Found found, Loaded loaded) {
        if (found.folder().isEmpty() || loaded.providers().stream().anyMatch(p -> p.backend() == Backend.IBMI)) {
            return Map.of();
        }
        Path folder = found.folder().get();
        if (loaded.problem().isPresent()) {
            return Map.of(Backend.IBMI, new StartOptions.Unavailable("GWY026", loaded.problem().get() + ", the JTOpen jar in " + folder + "; it may be incomplete"
                    + " (see " + DOCUMENTATION + ")"));
        }
        String where = Files.isDirectory(folder) ? "into the folder " + folder : "into the folder " + folder + ", which does not exist";
        String jar = "jt400-" + VERIFIED_JT400.get(VERIFIED_JT400.size() - 1) + ".jar";
        return Map.of(Backend.IBMI, new StartOptions.Unavailable("GWY026", "needs JTOpen, which is not part of the gateway: put " + jar + " " + where
                + " (see " + DOCUMENTATION + ")"));
    }

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
