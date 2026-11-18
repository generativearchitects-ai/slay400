package ai.generativearchitects.slay400.gateway.ibmi;

import static org.assertj.core.api.Assertions.assertThat;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.CharConverter;
import com.ibm.as400.access.CommandCall;
import com.ibm.as400.access.IFSFile;
import com.ibm.as400.access.IFSFileInputStream;
import com.ibm.as400.access.IFSFileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The setup tool of the {@code ibmi} profile, run by the workflow before the other tests. It copies the sources of
 * {@code ibmi/programs/} to a folder in the owner's home folder, compiles each into the owner's library with the PCML written
 * to a stream file, and brings the PCML files back to {@code target/ibmi/pcml/}, with the library replaced by
 * {@value IbmiSystem#LIBRARY_PLACEHOLDER}. It compares each with the PCML the repository keeps under {@code fixtures/ibmi},
 * which is the compiler's own output (decision 119): a difference, or a program without a fixture, fails the run, since it
 * means that a source or the compiler changed. The differences are written to {@code target/ibmi/pcml-differences.md}.
 *
 * <p>Idempotent: sources and PCML are overwritten, programs replaced. It leaves nothing outside that library and that folder:
 * the compiler writes no listing ({@code OUTPUT(*NONE)}). Every program is compiled even when one fails; a program that fails
 * is compiled once more with a listing, which is copied to the folder and its spooled file deleted, so that the compiler's
 * messages reach the report. One connection, ten compiles.
 */
@EnabledIfEnvironmentVariable(named = IbmiSystem.HOST, matches = ".+")
@EnabledIfEnvironmentVariable(named = IbmiSystem.USER, matches = ".+")
@EnabledIfEnvironmentVariable(named = IbmiSystem.PASSWORD, matches = ".+")
@EnabledIfEnvironmentVariable(named = IbmiSystem.LIBRARY, matches = ".+")
class IbmiSetupIT {

    static final List<String> PROGRAMS = List.of("TSTSCALAR", "TSTSTRUCT", "TSTARRAY", "TSTNESTED", "TSTWAIT", "TSTSIGNS", "TSTVARCHAR", "TSTFLOAT",
            "TSTDATES", "TSTCOUNTS");

    /** At most this many lines of compiler messages per program in the report. */
    static final int MAX_LISTING_LINES = 40;

    @Test
    void compilesTheTestProgramsAndBringsBackTheirPcml() throws Exception {
        Path out = IbmiSystem.output().resolve("pcml");
        Files.createDirectories(out);
        List<String> report = new ArrayList<>(List.of("# PCML written by the compiler, compared with the fixtures", ""));
        List<String> failures = new ArrayList<>();
        AS400 system = IbmiSystem.connect();
        try {
            report.add("Job CCSID of the connection: " + system.getCcsid() + ".");
            report.add("");
            IFSFile folder = new IFSFile(system, IbmiSystem.folder());
            if (!folder.exists() && !folder.mkdirs()) {
                failures.add("the folder for the sources could not be created in the home folder");
            }
            for (String program : PROGRAMS) {
                String source = Files.readString(IbmiSystem.root().resolve("ibmi/programs/" + program + ".rpgle"));
                String sourcePath = IbmiSystem.folder() + "/" + program + ".rpgle";
                String pcmlPath = IbmiSystem.folder() + "/" + program + ".pcml";
                IFSFile sourceFile = new IFSFile(system, sourcePath);
                if (sourceFile.exists()) {
                    sourceFile.delete();
                }
                try (IFSFileOutputStream stream = new IFSFileOutputStream(system, sourcePath, IFSFileOutputStream.SHARE_NONE, false, 1208)) {
                    stream.write(source.getBytes(StandardCharsets.UTF_8));
                }
                CommandCall command = new CommandCall(system);
                command.setThreadSafe(false);
                String compile = "CRTBNDRPG PGM(" + IbmiSystem.library() + "/" + program + ") SRCSTMF('" + sourcePath + "') PGMINFO(*PCML) INFOSTMF('" + pcmlPath
                        + "') OUTPUT(*NONE) REPLACE(*YES) DBGVIEW(*NONE) TGTCCSID(*JOB)";
                boolean compiled = command.run(compile);
                String messages = Arrays.stream(command.getMessageList()).map(m -> m.getID() + " " + m.getText()).collect(Collectors.joining("; "));
                System.out.println(IbmiSystem.redact(program + ": " + (compiled ? "compiled" : "not compiled") + (messages.isEmpty() ? "" : ": " + messages)));
                if (!compiled) {
                    failures.add(IbmiSystem.redact(program + " did not compile: " + messages));
                    report.add("## " + program);
                    report.add("");
                    report.add("Not compiled: " + IbmiSystem.redact(messages));
                    report.add("");
                    report.add("The compiler's messages:");
                    report.add("");
                    listing(system, program, sourcePath).forEach(line -> report.add("    " + IbmiSystem.redact(line)));
                    report.add("");
                    continue;
                }
                IFSFile pcmlFile = new IFSFile(system, pcmlPath);
                byte[] bytes;
                try (IFSFileInputStream stream = new IFSFileInputStream(pcmlFile)) {
                    bytes = stream.readAllBytes();
                }
                int ccsid = pcmlFile.getCCSID();
                String pcml = ccsid == 1208 ? new String(bytes, StandardCharsets.UTF_8) : new CharConverter(ccsid, system).byteArrayToString(bytes);
                String redacted = IbmiSystem.redactPcml(pcml);
                Files.writeString(out.resolve(program + ".pcml"), redacted);
                Path fixtureFile = IbmiSystem.root().resolve("fixtures/ibmi/apis/" + program + "/" + program + ".pcml");
                if (!Files.exists(fixtureFile)) {
                    failures.add(program + " has no fixture yet: commit pcml/" + program + ".pcml of the artifact as " + IbmiSystem.root().relativize(fixtureFile));
                    report.add("## " + program);
                    report.add("");
                    report.add("No fixture yet.");
                    report.add("");
                    continue;
                }
                List<String> differences = PcmlComparison.differences(Files.readString(fixtureFile), redacted);
                if (!differences.isEmpty()) {
                    failures.add(program + " differs from its fixture in " + differences.size() + " places: a source or the compiler changed (see pcml-differences.md)");
                }
                report.add("## " + program);
                report.add("");
                report.add("Stream file CCSID: " + ccsid + ".");
                report.add("");
                if (differences.isEmpty()) {
                    report.add("No difference.");
                } else {
                    differences.forEach(difference -> report.add("- " + difference));
                }
                report.add("");
            }
        } finally {
            system.disconnectAllServices();
        }
        String text = String.join("\n", report) + "\n";
        Files.writeString(IbmiSystem.output().resolve("pcml-differences.md"), text);
        System.out.println(text);
        assertThat(failures).isEmpty();
    }

    /**
     * The compiler's diagnostic lines for a program that does not compile: it is compiled once more with a listing, the listing
     * is copied to the test folder and its spooled file deleted, so that nothing is left outside that folder and the library.
     */
    static List<String> listing(AS400 system, String program, String sourcePath) throws Exception {
        String listingPath = IbmiSystem.folder() + "/" + program + ".lst";
        List<String> lines = new ArrayList<>();
        CommandCall command = new CommandCall(system);
        command.setThreadSafe(false);
        command.run("CRTBNDRPG PGM(" + IbmiSystem.library() + "/" + program + ") SRCSTMF('" + sourcePath + "') OUTPUT(*PRINT) REPLACE(*YES) DBGVIEW(*NONE) TGTCCSID(*JOB)");
        boolean copied = command.run("CPYSPLF FILE(" + program + ") TOFILE(*TOSTMF) JOB(*) SPLNBR(*LAST) TOSTMF('" + listingPath + "') STMFOPT(*REPLACE)");
        if (!copied) {
            lines.add("(the listing could not be copied: " + Arrays.stream(command.getMessageList()).map(m -> m.getID()).collect(Collectors.joining(", ")) + ")");
        }
        boolean deleted = command.run("DLTSPLF FILE(" + program + ") JOB(*) SPLNBR(*LAST)");
        System.out.println(program + ": listing " + (copied ? "copied" : "not copied") + ", spooled file " + (deleted ? "deleted" : "not deleted"));
        if (!copied) {
            return lines;
        }
        IFSFile file = new IFSFile(system, listingPath);
        byte[] bytes;
        try (IFSFileInputStream stream = new IFSFileInputStream(file)) {
            bytes = stream.readAllBytes();
        }
        int ccsid = file.getCCSID();
        String text = ccsid == 1208 ? new String(bytes, StandardCharsets.UTF_8) : new CharConverter(ccsid, system).byteArrayToString(bytes);
        text.lines().filter(line -> line.matches(".*\\bRN[FS][0-9]{4}\\b.*")).map(String::strip).distinct().limit(MAX_LISTING_LINES).forEach(lines::add);
        return lines;
    }
}
