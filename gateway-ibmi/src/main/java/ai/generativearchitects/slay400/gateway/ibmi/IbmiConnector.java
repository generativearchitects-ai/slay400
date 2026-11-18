package ai.generativearchitects.slay400.gateway.ibmi;

/** Opens connections to the IBM i of one environment. */
@FunctionalInterface
public interface IbmiConnector {

    /**
     * Opens a connection: resolves the host, connects, signs on and sets the library list.
     *
     * @throws IbmiFailure if any of that did not work
     */
    IbmiConnection open() throws IbmiFailure;
}
