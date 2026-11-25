package ai.generativearchitects.slay400.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The fixed statement of decision 149, word for word, wherever the gateway is distributed: its README, the third-party
 * notices of the distribution and the description of the image. Line breaks of the Markdown files do not count; every word,
 * quote and full stop does.
 */
class Jt400NoticeTest {

    /** The notice form of decision 149, as the decision writes it. */
    static final String NOTICE = "This distribution does not contain JT400 (JTOpen, the IBM Toolbox for Java). The `ibmi` backend needs it at run time. "
            + "Operators obtain it themselves, under its own licence, the IBM Public License 1.0; the documentation explains how to download and add it.";

    /** The text with every run of white space made one space. */
    private static String text(Path file) throws IOException {
        return Files.readString(file).replaceAll("\\s+", " ");
    }

    @Test
    void theReadmeCarriesTheNotice() throws IOException {
        assertThat(text(Path.of("../README.md"))).contains(NOTICE);
    }

    @Test
    void theNoticesOfTheDistributionCarryTheNotice() throws IOException {
        assertThat(text(Path.of("src/dist/THIRD-PARTY-NOTICES.md"))).contains(NOTICE);
    }

    @Test
    void theDescriptionOfTheImageIsTheNotice() throws IOException {
        assertThat(Files.readString(Path.of("../Dockerfile"))).contains("org.opencontainers.image.description=\"" + NOTICE + "\"");
    }
}
