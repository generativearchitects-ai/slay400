package ai.generativearchitects.slay400.gateway.ibmi;

import java.util.Objects;
import java.util.Optional;

/**
 * One parameter of a program call as bytes: what is sent, and how many bytes come back.
 *
 * @param input        the bytes sent to the program; empty for an output-only parameter
 * @param outputLength how many bytes the program returns in the parameter; 0 for an input-only parameter
 */
public record ParameterBytes(Optional<byte[]> input, int outputLength) {

    /**
     * @throws NullPointerException     if {@code input} is {@code null}
     * @throws IllegalArgumentException if {@code outputLength} is negative, or there is neither input nor output
     */
    public ParameterBytes {
        Objects.requireNonNull(input, "input");
        input = input.map(byte[]::clone);
        if (outputLength < 0 || input.isEmpty() && outputLength == 0) {
            throw new IllegalArgumentException("a parameter has input, output or both");
        }
    }

    /** A copy of the input. */
    @Override
    public Optional<byte[]> input() {
        return input.map(byte[]::clone);
    }

    /** Sizes only: never the bytes. */
    @Override
    public String toString() {
        return "ParameterBytes[input=" + input.map(b -> b.length + " bytes").orElse("none") + ", outputLength=" + outputLength + "]";
    }

    /** An input-only parameter. */
    public static ParameterBytes in(byte[] input) {
        return new ParameterBytes(Optional.of(input), 0);
    }

    /** An output-only parameter. */
    public static ParameterBytes out(int length) {
        return new ParameterBytes(Optional.empty(), length);
    }

    /** A parameter sent and returned. */
    public static ParameterBytes both(byte[] input) {
        return new ParameterBytes(Optional.of(input), input.length);
    }
}
