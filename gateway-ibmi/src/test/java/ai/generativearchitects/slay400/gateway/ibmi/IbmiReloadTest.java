package ai.generativearchitects.slay400.gateway.ibmi;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.Fixtures;
import ai.generativearchitects.slay400.gateway.LogCapture;
import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.StartOptions;
import ai.generativearchitects.slay400.gateway.load.Started;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeRequest;
import ai.generativearchitects.slay400.gateway.pipeline.ServedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A reload with the {@code ibmi} backend, on the fake connection (kickoff 07): the environment's pool and its connections
 * survive a switch, the layouts are those of the new set, and a set the layouts refuse is not served.
 */
class IbmiReloadTest {

    @TempDir
    Path dir;

    private final List<String> access = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private long tick = Instant.parse("2026-11-01T00:00:00Z").getEpochSecond();

    static final String CONFIG = IbmiPipelineTest.CONFIG.replace("bundles: [./ibmi-1.0.0.rpgapi]", "bundles: [./bundles]");

    private Started start(FakeIbmi fake) throws IOException {
        place(Fixtures.bundle(dir, "ibmi"), "ibmi.rpgapi");
        IbmiBackend backend = new IbmiBackend(settings -> fake, Duration.ofMillis(200), settings -> Duration.ofMillis(300));
        return GatewayLoader.load(Fixtures.write(dir, "gateway.yaml", CONFIG), IbmiPipelineTest.VARIABLES::get, access::add,
                new StartOptions(List.of(backend), true).withEvents(events::add)).started().orElseThrow();
    }

    private void place(Path bundle, String name) throws IOException {
        Path target = dir.resolve("bundles").resolve(name);
        Files.createDirectories(target.getParent());
        Files.copy(bundle, target, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(target, FileTime.from(Instant.ofEpochSecond(++tick)));
    }

    /** A bundle of one invented program with one character output, answering on {@code POST <route>}. */
    private Path program(String program, String route, String extra) {
        IbmiFixtures.operation(dir, program, IbmiFixtures.pcml(program, "", ProgramLayoutTest.CHAR_OUT + extra, ""),
                "program: " + program + "\npcml: " + program + ".pcml\nroute: " + route + "\nmethod: POST\nversion: 1.0.0\n");
        return dir.resolve(program + ".rpgapi");
    }

    private static void settle(Started started) {
        started.reloader().poll();
        started.reloader().poll();
    }

    private static int post(Started started, String path) {
        var response = started.gateway().handle(new RuntimeRequest("POST", path, Map.of(), Map.of(), Optional.empty(), () -> new byte[0]));
        return response.status();
    }

    @Test
    void thePoolsOfTheEnvironmentAreTheSameObjectsAfterASwitch() throws IOException {
        FakeIbmi fake = new FakeIbmi();
        Started started = start(fake);
        assertThat(fake.opened).hasValue(1);
        IbmiBackend.Environment environment = (IbmiBackend.Environment) started.backends().get(0);
        ConnectionPool pool = environment.pool();
        ServedSet before = started.gateway().served();
        assertThat(post(started, "/acme/test/added")).isEqualTo(404);

        place(program("TSTADDED", "/added", ""), "added.rpgapi");
        settle(started);
        assertThat(started.gateway().served()).isNotSameAs(before);
        assertThat(events).hasSize(1);
        // The same open backend, the same pool, and no new connection: the one of the start serves the new set.
        assertThat(started.backends()).containsExactly(environment);
        assertThat(environment.pool()).isSameAs(pool);
        assertThat(post(started, "/acme/test/added")).isEqualTo(200);
        assertThat(fake.opened).hasValue(1);
        assertThat(fake.connections).hasSize(1);
        // The callers of the two sets are different objects over that pool.
        assertThat(started.gateway().served().tenants().get("acme").environments().get("test").caller())
                .isNotSameAs(before.tenants().get("acme").environments().get("test").caller());
        started.close();
        assertThat(fake.connections.get(0).closed).hasValue(1);
    }

    @Test
    void aSetTheLayoutsRefuseIsNotServed() throws IOException {
        FakeIbmi fake = new FakeIbmi();
        Started started = start(fake);
        ServedSet before = started.gateway().served();
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            place(program("TSTLJUL", "/refused", "    <data name=\"D\" type=\"date\" dateformat=\"LONGJUL\" dateseparator=\"slash\" usage=\"output\"/>\n"),
                    "refused.rpgapi");
            settle(started);
            settle(started);
            List<String> warnings = log.lines().stream().filter(l -> l.level() == Level.WARNING).map(LogCapture.Line::message).toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0)).contains("GWY017").contains("the date format LONGJUL");
        }
        assertThat(started.gateway().served()).isSameAs(before);
        assertThat(post(started, "/acme/test/refused")).isEqualTo(404);
        assertThat(events).isEmpty();
        started.close();
    }
}
