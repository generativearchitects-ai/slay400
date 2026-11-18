package ai.generativearchitects.slay400.gateway.ibmi;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.LogCapture;
import ai.generativearchitects.slay400.gateway.call.BackendStart;
import ai.generativearchitects.slay400.gateway.call.CallContext;
import ai.generativearchitects.slay400.gateway.call.CallOutcome;
import ai.generativearchitects.slay400.gateway.call.ProgramCaller;
import ai.generativearchitects.slay400.gateway.config.Backend;
import ai.generativearchitects.slay400.gateway.config.Credentials;
import ai.generativearchitects.slay400.gateway.config.EnvironmentConfig;
import ai.generativearchitects.slay400.gateway.config.IbmiSettings;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.codec.ApiValue;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every row of the outcomes table of the brief, the pool and the time-out, with a fake connection. Every value is invented. */
class IbmiCallerTest {

    @TempDir
    static Path dir;
    static Map<String, ApiOperation> operations;

    static final String HOST = "host-marker.invalid";
    static final String USER = "USERMARK";
    static final String PASSWORD = "PW-MARKER-5e1";
    static final String LIBRARY = "LIBMARK";
    static final CallContext CONTEXT = new CallContext("acme", "test", "corr-1");

    @BeforeAll
    static void build() {
        operations = IbmiFixtures.operations(dir);
    }

    static IbmiSettings settings(int maxConnections) {
        return new IbmiSettings(HOST, List.of(LIBRARY, "OTHERLIB"), new Credentials(USER, PASSWORD.toCharArray()), true, maxConnections, 30);
    }

    static EnvironmentConfig environment(int maxConnections) {
        return new EnvironmentConfig("test", "T0001", Backend.IBMI, Optional.of(settings(maxConnections)), new SourceLocation("gateway.yaml", 1, 1));
    }

    /** A backend on the fake, with a short pool wait and call time-out. */
    static BackendStart start(FakeIbmi fake, int maxConnections, Duration timeout, boolean connectCheck) {
        IbmiBackend backend = new IbmiBackend(settings -> fake, Duration.ofMillis(200), settings -> timeout);
        return backend.start("acme", environment(maxConnections), List.copyOf(operations.values()), connectCheck);
    }

    static ProgramCaller caller(FakeIbmi fake) {
        return start(fake, 1, Duration.ofSeconds(5), true).caller().orElseThrow();
    }

    static List<WireValue> input(String id, Map<String, ApiValue> request) {
        return OperationCodec.of(operations.get(id)).encode(request, Map.of()).parameters().orElseThrow();
    }

    static List<WireValue> waitInput() {
        return input("tstwait", Map.of("seconds", new ApiValue.Integer(BigInteger.ONE)));
    }

    // ---- Returned

    @Test
    void aProgramThatReturnsIsReturnedWithItsParametersDecoded() {
        FakeIbmi fake = new FakeIbmi();
        fake.program = (path, parameters) -> new CallReply.Completed(List.of(new byte[0], new byte[] {(byte) 0xE8}));
        CallOutcome outcome = caller(fake).call(CONTEXT, operations.get("tstwait"), waitInput());
        assertThat(outcome).isEqualTo(new CallOutcome.Returned(List.of(waitInput().get(0), new WireValue.Text("Y"))));
        assertThat(fake.paths).containsExactly("/QSYS.LIB/%LIBL%.LIB/TSTWAIT.PGM");
        // One connection, opened by the check at start and used for the call.
        assertThat(fake.opened).hasValue(1);
    }

    @Test
    void theJobCcsidOfTheConnectionIsUsedForCharacterData() {
        FakeIbmi fake = new FakeIbmi();
        fake.ccsid = 500;
        List<byte[]> sent = new ArrayList<>();
        fake.program = (path, parameters) -> {
            sent.add(parameters.get(0).input().orElseThrow());
            return new CallReply.Completed(List.of(parameters.get(0).input().orElseThrow()));
        };
        ApiOperation operation = operations.get("tstnone");
        CallOutcome outcome = caller(fake).call(CONTEXT, operation, List.of(new WireValue.Text("!")));
        assertThat(sent.get(0)).containsExactly(0x4F);
        assertThat(outcome).isEqualTo(new CallOutcome.Returned(List.of(new WireValue.Text("!"))));
    }

    // ---- Unavailable

    @Test
    void aHostThatCannotBeReachedAtStartStopsTheStartWithoutNamingIt() {
        FakeIbmi fake = new FakeIbmi();
        fake.openFailure = new IbmiFailure(IbmiFailure.Kind.UNREACHABLE, "the host name does not resolve");
        BackendStart start = start(fake, 1, Duration.ofSeconds(5), true);
        assertThat(start.caller()).isEmpty();
        assertThat(start.problems()).containsExactly("the host cannot be reached (the host name does not resolve)");
    }

    @Test
    void withoutTheConnectCheckNothingIsOpenedAtStartAndAnUnreachableHostIsUnavailable() {
        FakeIbmi fake = new FakeIbmi();
        fake.openFailure = new IbmiFailure(IbmiFailure.Kind.UNREACHABLE, "ConnectException");
        ProgramCaller caller = start(fake, 1, Duration.ofSeconds(5), false).caller().orElseThrow();
        assertThat(fake.opened).hasValue(0);
        try (LogCapture log = new LogCapture("slay400.gateway.ibmi")) {
            assertThat(caller.call(CONTEXT, operations.get("tstwait"), waitInput())).isEqualTo(new CallOutcome.Unavailable());
            assertThat(log.text()).contains("No connection to the IBM i of tenant acme, environment test: the host cannot be reached (ConnectException)")
                    .contains("correlation id corr-1");
        }
        // The next call tries again: an unreachable host is not a refused sign-on.
        fake.openFailure = null;
        assertThat(caller.call(CONTEXT, operations.get("tstwait"), waitInput())).isInstanceOf(CallOutcome.Returned.class);
    }

    @Test
    void aRefusedSignOnIsLoggedOnceAtErrorAndNeverTriedAgain() {
        FakeIbmi fake = new FakeIbmi();
        fake.openFailure = new IbmiFailure(IbmiFailure.Kind.SIGN_ON_REFUSED, "return code 2");
        ProgramCaller caller = start(fake, 2, Duration.ofSeconds(5), false).caller().orElseThrow();
        try (LogCapture log = new LogCapture("slay400.gateway.ibmi")) {
            for (int i = 0; i < 5; i++) {
                assertThat(caller.call(CONTEXT, operations.get("tstwait"), waitInput())).isEqualTo(new CallOutcome.Unavailable());
            }
            assertThat(fake.opened).hasValue(1);
            assertThat(log.lines().stream().filter(line -> line.level() == Level.SEVERE)).singleElement()
                    .satisfies(line -> assertThat(line.message()).contains("refused the sign-on").contains("does not sign on again until it restarts"));
            assertThat(log.text()).doesNotContain(USER).doesNotContain(PASSWORD).doesNotContain(HOST);
        }
        fake.openFailure = null;
        assertThat(caller.call(CONTEXT, operations.get("tstwait"), waitInput())).isEqualTo(new CallOutcome.Unavailable());
        assertThat(fake.opened).hasValue(1);
    }

    @Test
    void aRefusedSignOnAtStartStopsTheStartAndIsRemembered() {
        FakeIbmi fake = new FakeIbmi();
        fake.openFailure = new IbmiFailure(IbmiFailure.Kind.SIGN_ON_REFUSED, "return code 2");
        BackendStart start = start(fake, 1, Duration.ofSeconds(5), true);
        assertThat(start.problems()).containsExactly("the sign-on was refused (return code 2)");
    }

    @Test
    void aLibraryListThatCannotBeSetStopsTheStart() {
        FakeIbmi fake = new FakeIbmi();
        fake.openFailure = new IbmiFailure(IbmiFailure.Kind.LIBRARY_LIST, "CPF2110");
        assertThat(start(fake, 1, Duration.ofSeconds(5), true).problems()).containsExactly("the library list cannot be set (CPF2110)");
        fake.openFailure = new IbmiFailure(IbmiFailure.Kind.BROKEN, "PropertyVetoException");
        assertThat(start(fake, 1, Duration.ofSeconds(5), true).problems()).containsExactly("the connection does not work (PropertyVetoException)");
    }

    @Test
    void whenEveryConnectionIsInUseACallWaitsBrieflyThenIsUnavailable() throws Exception {
        FakeIbmi fake = new FakeIbmi();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fake.program = (path, parameters) -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return FakeIbmi.completed(parameters);
        };
        ProgramCaller caller = caller(fake);
        Thread busy = Thread.ofVirtual().start(() -> caller.call(CONTEXT, operations.get("tstwait"), waitInput()));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();
        try (LogCapture log = new LogCapture("slay400.gateway.ibmi")) {
            assertThat(caller.call(CONTEXT, operations.get("tstwait"), waitInput())).isEqualTo(new CallOutcome.Unavailable());
            assertThat(log.text()).contains("every connection is in use and none became free within 200 ms");
        }
        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(190);
        release.countDown();
        busy.join(5000);
        // The pool is bounded: still one connection.
        assertThat(fake.opened).hasValue(1);
        assertThat(caller.call(CONTEXT, operations.get("tstwait"), waitInput())).isInstanceOf(CallOutcome.Returned.class);
    }

    @Test
    void thePoolOpensUpToItsSizeAndReusesIdleConnections() throws Exception {
        FakeIbmi fake = new FakeIbmi();
        CountDownLatch inside = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        fake.program = (path, parameters) -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return FakeIbmi.completed(parameters);
        };
        ProgramCaller caller = start(fake, 3, Duration.ofSeconds(5), false).caller().orElseThrow();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            threads.add(Thread.ofVirtual().start(() -> caller.call(CONTEXT, operations.get("tstwait"), waitInput())));
        }
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(caller.call(CONTEXT, operations.get("tstwait"), waitInput())).isEqualTo(new CallOutcome.Unavailable());
        release.countDown();
        for (Thread thread : threads) {
            thread.join(5000);
        }
        assertThat(fake.opened).hasValue(3);
        fake.program = (path, parameters) -> FakeIbmi.completed(parameters);
        for (int i = 0; i < 5; i++) {
            assertThat(caller.call(CONTEXT, operations.get("tstwait"), waitInput())).isInstanceOf(CallOutcome.Returned.class);
        }
        assertThat(fake.opened).hasValue(3);
    }

    @Test
    void aBrokenConnectionIsReplacedBeforeUse() {
        FakeIbmi fake = new FakeIbmi();
        ProgramCaller caller = caller(fake);
        FakeIbmi.Connection first = fake.connections.get(0);
        first.alive = false;
        try (LogCapture log = new LogCapture("slay400.gateway.ibmi")) {
            assertThat(caller.call(CONTEXT, operations.get("tstwait"), waitInput())).isInstanceOf(CallOutcome.Returned.class);
            assertThat(log.text()).contains("no longer worked and is replaced");
        }
        assertThat(first.closed).hasValue(1);
        assertThat(first.calls).hasValue(0);
        assertThat(fake.opened).hasValue(2);
        assertThat(fake.connections.get(1).calls).hasValue(1);
    }

    // ---- TimedOut

    @Test
    void noAnswerWithinTheTimeOutIsTimedOutAndTheConnectionIsClosed() {
        FakeIbmi fake = new FakeIbmi();
        CountDownLatch never = new CountDownLatch(1);
        fake.program = (path, parameters) -> {
            never.await(10, TimeUnit.SECONDS);
            return FakeIbmi.completed(parameters);
        };
        ProgramCaller caller = start(fake, 1, Duration.ofMillis(150), true).caller().orElseThrow();
        long started = System.nanoTime();
        try (LogCapture log = new LogCapture("slay400.gateway.ibmi")) {
            assertThat(caller.call(CONTEXT, operations.get("tstwait"), waitInput())).isEqualTo(new CallOutcome.TimedOut());
            assertThat(log.text()).contains("did not answer within 150 ms; its connection is closed, correlation id corr-1");
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(millis).isGreaterThanOrEqualTo(140).isLessThan(5000);
        assertThat(fake.connections.get(0).closed).hasValue(1);
        never.countDown();
        // The place of the closed connection is free again: the next call opens a new one.
        fake.program = (path, parameters) -> FakeIbmi.completed(parameters);
        assertThat(caller.call(CONTEXT, operations.get("tstwait"), waitInput())).isInstanceOf(CallOutcome.Returned.class);
        assertThat(fake.opened).hasValue(2);
    }

    // ---- Failed

    @Test
    void anEscapeMessageIsFailedWithTheMessageIdsAndTheTextWithoutHostUserOrLibrary() {
        FakeIbmi fake = new FakeIbmi();
        fake.program = (path, parameters) -> new CallReply.Escaped(List.of(new IbmiMessage("CPF9898", "Escape from LIBMARK/TSTWAIT for user usermark on " + HOST),
                new IbmiMessage("", "second")));
        CallOutcome outcome = caller(fake).call(CONTEXT, operations.get("tstwait"), waitInput());
        assertThat(outcome).isInstanceOf(CallOutcome.Failed.class);
        CallOutcome.Failed failed = (CallOutcome.Failed) outcome;
        assertThat(failed.messageIds()).containsExactly("CPF9898");
        assertThat(failed.reason()).isEqualTo("the call of TSTWAIT ended with: CPF9898 Escape from <library>/TSTWAIT for user <user> on <host>; second");
        // The connection still works and serves the next call.
        assertThat(fake.connections.get(0).closed).hasValue(0);
    }

    @Test
    void aProgramThatIsNotFoundIsFailedWithItsMessage() {
        FakeIbmi fake = new FakeIbmi();
        fake.program = (path, parameters) -> new CallReply.Escaped(List.of(new IbmiMessage("MCH3401", "Cannot resolve to object TSTNONE.")));
        CallOutcome outcome = caller(fake).call(CONTEXT, operations.get("tstnone"), List.of(new WireValue.Text(" ")));
        assertThat(outcome).isEqualTo(new CallOutcome.Failed("the call of TSTNONE ended with: MCH3401 Cannot resolve to object TSTNONE.", List.of("MCH3401")));
        fake.program = (path, parameters) -> new CallReply.Escaped(List.of());
        assertThat(((CallOutcome.Failed) caller(fake).call(CONTEXT, operations.get("tstnone"), List.of(new WireValue.Text(" ")))).reason()).endsWith("ended with: no message");
    }

    @Test
    void aConnectionThatBreaksDuringTheCallIsFailedAndClosed() {
        FakeIbmi fake = new FakeIbmi();
        fake.program = (path, parameters) -> {
            throw new IbmiFailure(IbmiFailure.Kind.BROKEN, "SocketException");
        };
        CallOutcome outcome = caller(fake).call(CONTEXT, operations.get("tstwait"), waitInput());
        assertThat(outcome).isEqualTo(new CallOutcome.Failed("the connection broke during the call of TSTWAIT: the connection does not work (SocketException)"));
        assertThat(fake.connections.get(0).closed).hasValue(1);
        fake.program = (path, parameters) -> {
            throw new IllegalStateException("surprise");
        };
        assertThat(((CallOutcome.Failed) caller(fake).call(CONTEXT, operations.get("tstwait"), waitInput())).reason()).endsWith("the call failed (IllegalStateException)");
    }

    @Test
    void outputThatIsNotAValueOfItsLayoutIsFailedAndInputThatDoesNotFitIsFailedBeforeTheCall() {
        FakeIbmi fake = new FakeIbmi();
        fake.program = (path, parameters) -> new CallReply.Completed(List.of(new byte[0], new byte[3]));
        ProgramCaller caller = caller(fake);
        assertThat(((CallOutcome.Failed) caller.call(CONTEXT, operations.get("tstwait"), waitInput())).reason())
                .isEqualTo("the output of TSTWAIT cannot be read: DONE: the program returned 3 bytes instead of 1");
        int calls = fake.paths.size();
        assertThat(((CallOutcome.Failed) caller.call(CONTEXT, operations.get("tstwait"), List.of(new WireValue.Text("x"), new WireValue.Text("y")))).reason())
                .isEqualTo("the input of TSTWAIT cannot be laid out: SECONDS: a whole number was expected");
        assertThat(fake.paths).hasSize(calls);
        // The connection went back to the pool both times.
        assertThat(caller.call(CONTEXT, operations.get("tstwait"), waitInput())).isInstanceOf(CallOutcome.Failed.class);
        assertThat(fake.opened).hasValue(1);
    }

    @Test
    void anOperationTheBackendWasNotStartedWithIsFailed() {
        FakeIbmi fake = new FakeIbmi();
        IbmiBackend backend = new IbmiBackend(settings -> fake, Duration.ofMillis(200), settings -> Duration.ofSeconds(1));
        ProgramCaller caller = backend.start("acme", environment(1), List.of(operations.get("tstwait")), false).caller().orElseThrow();
        assertThat(caller.call(CONTEXT, operations.get("tstnone"), List.of(new WireValue.Text(" ")))).isEqualTo(new CallOutcome.Failed("the operation tstnone has no layout"));
    }

    @Test
    void anInterruptedCallIsFailedClosesTheConnectionAndKeepsTheInterrupt() {
        FakeIbmi fake = new FakeIbmi();
        fake.program = (path, parameters) -> {
            Thread.sleep(10_000);
            return FakeIbmi.completed(parameters);
        };
        ProgramCaller caller = caller(fake);
        Thread.currentThread().interrupt();
        CallOutcome outcome;
        try {
            outcome = caller.call(CONTEXT, operations.get("tstwait"), waitInput());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        // Interrupted while waiting for a connection, or for the call.
        assertThat(outcome).isIn(new CallOutcome.Unavailable(), new CallOutcome.Failed("interrupted during the call of TSTWAIT"));
    }

    // ---- closing

    @Test
    void closingTheCallerClosesIdleConnectionsAndThoseGivenBackLater() throws Exception {
        FakeIbmi fake = new FakeIbmi();
        ProgramCaller caller = start(fake, 2, Duration.ofSeconds(5), true).caller().orElseThrow();
        caller.close();
        assertThat(fake.connections.get(0).closed).hasValue(1);
        assertThat(caller.call(CONTEXT, operations.get("tstwait"), waitInput())).isEqualTo(new CallOutcome.Unavailable());
        // A call that was running when the gateway stopped closes its connection when it ends.
        FakeIbmi other = new FakeIbmi();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        other.program = (path, parameters) -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return FakeIbmi.completed(parameters);
        };
        ProgramCaller running = start(other, 1, Duration.ofSeconds(5), false).caller().orElseThrow();
        Thread call = Thread.ofVirtual().start(() -> running.call(CONTEXT, operations.get("tstwait"), waitInput()));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        running.close();
        release.countDown();
        call.join(5000);
        assertThat(other.connections.get(0).closed).hasValue(1);
    }

    // ---- what is never logged

    @Test
    void aMarkerInEveryParameterValueAndInTheCredentialsIsNeverLogged() {
        String marker = "MK9";
        FakeIbmi fake = new FakeIbmi();
        List<CallReply> replies = new ArrayList<>(List.of(new CallReply.Escaped(List.of(new IbmiMessage("CPF0001", "Escape for " + USER + " in " + LIBRARY)))));
        fake.program = (path, parameters) -> replies.isEmpty() ? FakeIbmi.completed(parameters) : replies.remove(0);
        try (LogCapture ibmi = new LogCapture("slay400.gateway.ibmi"); LogCapture gateway = new LogCapture("slay400.gateway")) {
            ProgramCaller caller = start(fake, 1, Duration.ofMillis(300), true).caller().orElseThrow();
            List<WireValue> nested = input("tstnested", Map.of("key", new ApiValue.Text(marker)));
            List<WireValue> scalars = input("tstscalar", Map.of("text", new ApiValue.Text(marker + "TEXT"), "packed",
                    new ApiValue.Decimal(new java.math.BigDecimal("98765.43")), "zoned", new ApiValue.Integer(BigInteger.valueOf(98765)), "whole",
                    new ApiValue.Integer(BigInteger.valueOf(987654321))));
            caller.call(CONTEXT, operations.get("tstnested"), nested);
            caller.call(CONTEXT, operations.get("tstscalar"), scalars);
            fake.program = (path, parameters) -> {
                Thread.sleep(2000);
                return FakeIbmi.completed(parameters);
            };
            caller.call(CONTEXT, operations.get("tstscalar"), scalars);
            fake.openFailure = new IbmiFailure(IbmiFailure.Kind.SIGN_ON_REFUSED, "return code 2");
            caller.call(CONTEXT, operations.get("tstscalar"), scalars);
            String all = ibmi.text() + gateway.text();
            assertThat(all).isNotEmpty().doesNotContain(marker).doesNotContain("98765").doesNotContain("987654321").doesNotContain(PASSWORD)
                    .doesNotContain(USER).doesNotContain(HOST).doesNotContain(LIBRARY);
        }
    }

    @Test
    void theRedactorReplacesWholeNamesOnlyRegardlessOfCase() {
        Redactor redactor = new Redactor(new IbmiSettings("sys.example", List.of("APPLIB", "A"), new Credentials("BOB", "pw".toCharArray()), true, 1, 1));
        assertThat(redactor.apply("Object in APPLIB not found for bob on SYS.EXAMPLE, APPLIBX kept, A/B, pw!"))
                .isEqualTo("Object in <library> not found for <user> on <host>, APPLIBX kept, <library>/B, <password>!");
        assertThat(new Redactor(new IbmiSettings("h", List.of("L"), new Credentials("U", new char[0]), true, 1, 1)).apply("nothing here")).isEqualTo("nothing here");
    }
}
