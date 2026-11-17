package ai.generativearchitects.slay400.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.pipeline.AccessRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AccessRecordTest {

    private static String json(String route) {
        return new AccessRecord(Instant.parse("2026-11-17T10:00:00Z"), Optional.empty(), Optional.empty(), Optional.empty(), "GET", Optional.of(route), 200, 5, "id",
                List.of("a", "b")).toJson();
    }

    @Test
    void escapesWhatJsonRequiresAndEveryControlCharacter() {
        assertThat(json("q\"b\\n\n\r\t\u001f\u007f ")).contains("\"route\":\"q\\\"b\\\\n\\n\\r\\t\\u001f\\u007f\\u2028\"");
    }

    @Test
    void aSpaceAndOrdinaryTextAreNotEscaped() {
        assertThat(json(" /a b/é")).contains("\"route\":\" /a b/é\"");
    }

    @Test
    void listsTheProblemCodesInOrder() {
        assertThat(json("/x")).contains("\"problems\":[\"a\",\"b\"]").doesNotContain("tenant").contains("\"durationMs\":5");
    }
}
