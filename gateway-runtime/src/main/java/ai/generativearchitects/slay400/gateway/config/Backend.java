package ai.generativearchitects.slay400.gateway.config;

/** How an environment's programs are called. Only {@link #MOCK} exists in this version; {@code ibmi} is recognised and refused. */
public enum Backend {
    /** The mock call strategy: answers from the bundle's mock definitions, no IBM i involved. */
    MOCK
}
