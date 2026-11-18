package ai.generativearchitects.slay400.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The command line: {@code --config <file>} and {@code --no-connect-check}, in any order, each once. */
class MainTest {

    @Test
    void theConfigurationFileAndTheConnectCheck() {
        assertThat(Main.parse(new String[] {"--config", "g.yaml"})).contains(new Main.Arguments(Path.of("g.yaml").toAbsolutePath(), true));
        assertThat(Main.parse(new String[] {"--config", "g.yaml", "--no-connect-check"})).contains(new Main.Arguments(Path.of("g.yaml").toAbsolutePath(), false));
        assertThat(Main.parse(new String[] {"--no-connect-check", "--config", "g.yaml"})).contains(new Main.Arguments(Path.of("g.yaml").toAbsolutePath(), false));
    }

    @Test
    void anythingElseIsAUsageError() {
        assertThat(Main.parse(new String[] {})).isEmpty();
        assertThat(Main.parse(new String[] {"--config"})).isEmpty();
        assertThat(Main.parse(new String[] {"--no-connect-check"})).isEmpty();
        assertThat(Main.parse(new String[] {"--config", "a", "--config", "b"})).isEmpty();
        assertThat(Main.parse(new String[] {"--config", "a", "--no-connect-check", "--no-connect-check"})).isEmpty();
        assertThat(Main.parse(new String[] {"--config", "a", "--verbose"})).isEmpty();
        assertThat(Main.parse(new String[] {"g.yaml"})).isEmpty();
        assertThat(Main.USAGE).isEqualTo("Usage: gateway --config <file> [--no-connect-check]");
    }
}
