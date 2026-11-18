package ai.generativearchitects.slay400.gateway.ibmi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A fake of the thin connection interface: what a test scripts, and what the backend did with it. */
final class FakeIbmi implements IbmiConnector {

    /** How a call on a fake connection behaves. */
    @FunctionalInterface
    interface Program {
        CallReply run(String path, List<ParameterBytes> parameters) throws IbmiFailure, InterruptedException;
    }

    final AtomicInteger opened = new AtomicInteger();
    final List<Connection> connections = new CopyOnWriteArrayList<>();
    final List<String> paths = new CopyOnWriteArrayList<>();
    volatile IbmiFailure openFailure;
    volatile Program program = (path, parameters) -> completed(parameters);
    volatile int ccsid = 37;

    /** Every parameter comes back as it was sent, or as zero bytes when it was output only. */
    static CallReply completed(List<ParameterBytes> parameters) {
        List<byte[]> output = new ArrayList<>();
        for (ParameterBytes parameter : parameters) {
            output.add(parameter.outputLength() == 0 ? new byte[0] : parameter.input().orElse(new byte[parameter.outputLength()]));
        }
        return new CallReply.Completed(output);
    }

    @Override
    public IbmiConnection open() throws IbmiFailure {
        opened.incrementAndGet();
        if (openFailure != null) {
            throw openFailure;
        }
        Connection connection = new Connection();
        connections.add(connection);
        return connection;
    }

    final class Connection implements IbmiConnection {
        volatile boolean alive = true;
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public int ccsid() {
            return ccsid;
        }

        @Override
        public boolean alive() {
            return alive && closed.get() == 0;
        }

        @Override
        public CallReply call(String programPath, List<ParameterBytes> parameters) throws IbmiFailure {
            calls.incrementAndGet();
            paths.add(programPath);
            try {
                return program.run(programPath, parameters);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IbmiFailure(IbmiFailure.Kind.BROKEN, "interrupted");
            }
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }
}
