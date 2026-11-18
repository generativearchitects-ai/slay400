package ai.generativearchitects.slay400.gateway.config;

/** How an environment's programs are called: the {@code backend} of the environment in the configuration file. */
public enum Backend {
    /** {@code mock}: the mock call strategy answers from the bundle's mock definitions, no IBM i involved. */
    MOCK,
    /** {@code ibmi}: the programs are called on an IBM i, with the settings of {@link IbmiSettings}. */
    IBMI
}
