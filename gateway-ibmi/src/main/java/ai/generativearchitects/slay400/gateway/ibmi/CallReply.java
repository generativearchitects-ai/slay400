package ai.generativearchitects.slay400.gateway.ibmi;

import java.util.List;

/** What a program call on the connection came to, when the connection itself worked. */
public sealed interface CallReply {

    /**
     * The program returned.
     *
     * @param output the bytes of every parameter after the call, in order; an input-only parameter has an empty array
     */
    record Completed(List<byte[]> output) implements CallReply {
        /** @throws NullPointerException if the list or an element is {@code null} */
        public Completed {
            output = output.stream().map(byte[]::clone).toList();
        }

        /** Copies of the bytes. */
        @Override
        public List<byte[]> output() {
            return output.stream().map(byte[]::clone).toList();
        }
    }

    /**
     * The call ended with an escape message, or the program was not found.
     *
     * @param messages the messages returned, in order
     */
    record Escaped(List<IbmiMessage> messages) implements CallReply {
        /** @throws NullPointerException if the list or an element is {@code null} */
        public Escaped {
            messages = List.copyOf(messages);
        }
    }
}
