package ai.generativearchitects.slay400.gateway.ibmi;

import java.util.List;

/**
 * One signed-on connection to an IBM i, with the library list of its environment set: the thin interface between the ibmi
 * backend and JT400, so that everything above it is tested without a system. One call at a time.
 */
public interface IbmiConnection extends AutoCloseable {

    /** The CCSID of the connection's job, for character data whose field names none. */
    int ccsid();

    /** Whether the connection still works. It may ask the system; it never throws. */
    boolean alive();

    /**
     * Calls a program and waits for it.
     *
     * @param programPath the integrated file system path of the program, through the library list
     * @param parameters  the parameters in order
     * @throws IbmiFailure if the connection did not work; the connection is then unusable
     */
    CallReply call(String programPath, List<ParameterBytes> parameters) throws IbmiFailure;

    /** Ends the connection. Never throws. */
    @Override
    void close();
}
