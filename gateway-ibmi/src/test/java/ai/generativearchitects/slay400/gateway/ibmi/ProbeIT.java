package ai.generativearchitects.slay400.gateway.ibmi;

import static org.assertj.core.api.Assertions.assertThat;

import com.ibm.as400.access.AS400Text;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * A probe of what the gateway cannot ask through its layout: how the system writes and reads midnight in the USA time format
 * (kickoff 05). It is called with the raw
 * call: its inputs as text written by hand in the job's CCSID, each output in a buffer of {@value #BUFFER} zero bytes, so that
 * the report shows exactly how many bytes the system writes and what text. The parameters are read from the compiler's PCML
 * that the setup tool of the same run brought back; a program without PCML did not compile, which the report says.
 * One call, one connection.
 */
@EnabledIfEnvironmentVariable(named = IbmiSystem.HOST, matches = ".+")
@EnabledIfEnvironmentVariable(named = IbmiSystem.USER, matches = ".+")
@EnabledIfEnvironmentVariable(named = IbmiSystem.PASSWORD, matches = ".+")
@EnabledIfEnvironmentVariable(named = IbmiSystem.LIBRARY, matches = ".+")
class ProbeIT {

    static final int BUFFER = 48;

    /** One call: a program and the text of each of its inputs. */
    record Probe(String program, String what, Map<String, String> inputs) {
    }

    static final List<Probe> PROBES = List.of(
            new Probe("TSTUSAMID", "midnight in the USA format: ISO midnight to USA, and the USA inputs 12:00 AM and 00:00 AM to ISO",
                    Map.of("ISOMID", "00.00.00", "USA12", "12:00 AM", "USA00", "00:00 AM")));

    /** A top-level parameter of the compiler's PCML. */
    record Parameter(String name, String usage, Map<String, String> attributes) {
    }

    static List<Parameter> parameters(String pcml) throws Exception {
        Element root = PcmlComparison.parse(pcml);
        List<Parameter> out = new ArrayList<>();
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element program && program.getTagName().equals("program")) {
                for (Node data = program.getFirstChild(); data != null; data = data.getNextSibling()) {
                    if (data instanceof Element element) {
                        Map<String, String> attributes = new LinkedHashMap<>();
                        for (int i = 0; i < element.getAttributes().getLength(); i++) {
                            attributes.put(element.getAttributes().item(i).getNodeName(), element.getAttributes().item(i).getNodeValue());
                        }
                        out.add(new Parameter(element.getAttribute("name"), element.getAttribute("usage"), attributes));
                    }
                }
            }
        }
        return out;
    }

    @Test
    void callsTheProgramsOfKickoff05AndReportsTheirBytes() throws Exception {
        Path pcmlDir = IbmiSystem.output().resolve("pcml");
        StringBuilder report = new StringBuilder("# Probe of the programs of kickoff 05\n\n");
        IbmiConnection connection = new Jt400Connector(IbmiSystem.settings(List.of(IbmiSystem.library()), 30)).open();
        int ccsid = connection.ccsid();
        report.append("Job CCSID of the connection: ").append(ccsid).append(". Each output is given a buffer of ").append(BUFFER)
                .append(" zero bytes; the bytes shown are those up to the last byte the program wrote.\n");
        try {
            for (Probe probe : PROBES) {
                report.append("\n## ").append(probe.program()).append(": ").append(probe.what()).append("\n\n");
                Path pcml = pcmlDir.resolve(probe.program() + ".pcml");
                if (!Files.exists(pcml)) {
                    report.append("Not called: the program did not compile in this run (see pcml-differences.md).\n");
                    continue;
                }
                List<Parameter> parameters = parameters(Files.readString(pcml));
                List<ParameterBytes> bytes = new ArrayList<>();
                for (Parameter parameter : parameters) {
                    String text = probe.inputs().get(parameter.name());
                    if (parameter.usage().equals("input")) {
                        bytes.add(ParameterBytes.in((byte[]) new AS400Text(text.length(), ccsid).toBytes(text)));
                    } else {
                        bytes.add(new ParameterBytes(Optional.of(new byte[BUFFER]), BUFFER));
                    }
                }
                long started = System.nanoTime();
                CallReply reply = connection.call("/QSYS.LIB/%LIBL%.LIB/" + probe.program() + ".PGM", bytes);
                long millis = (System.nanoTime() - started) / 1_000_000;
                if (reply instanceof CallReply.Escaped escaped) {
                    report.append("The call ended with: ");
                    escaped.messages().forEach(m -> report.append(m.id()).append(' ').append(m.text()).append("; "));
                    report.append("(").append(millis).append(" ms)\n");
                    continue;
                }
                List<byte[]> output = ((CallReply.Completed) reply).output();
                report.append("| Parameter | PCML | Sent | Bytes returned | Text |\n|---|---|---|---|---|\n");
                for (int i = 0; i < parameters.size(); i++) {
                    Parameter parameter = parameters.get(i);
                    Map<String, String> shown = new LinkedHashMap<>(parameter.attributes());
                    shown.remove("name");
                    String sent = probe.inputs().getOrDefault(parameter.name(), "");
                    if (parameter.usage().equals("input")) {
                        report.append("| ").append(parameter.name()).append(" | `").append(shown).append("` | `").append(sent).append("` | | |\n");
                        continue;
                    }
                    byte[] returned = output.get(i);
                    int end = returned.length;
                    while (end > 0 && returned[end - 1] == 0) {
                        end--;
                    }
                    String hex = HexFormat.of().withUpperCase().formatHex(returned, 0, end);
                    String text = end == 0 ? "" : (String) new AS400Text(end, ccsid).toObject(java.util.Arrays.copyOf(returned, end));
                    report.append("| ").append(parameter.name()).append(" | `").append(shown).append("` | | `").append(hex).append("` (").append(end).append(" bytes) | `")
                            .append(text).append("` |\n");
                }
                report.append("\n(").append(millis).append(" ms)\n");
            }
        } finally {
            connection.close();
        }
        String text = IbmiSystem.redact(report.toString());
        Files.createDirectories(IbmiSystem.output());
        Files.writeString(IbmiSystem.output().resolve("probe-report.md"), text);
        System.out.println(text);
        assertThat(text).isNotEmpty();
    }
}
