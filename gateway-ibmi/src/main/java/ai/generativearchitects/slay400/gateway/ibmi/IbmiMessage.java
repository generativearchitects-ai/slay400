package ai.generativearchitects.slay400.gateway.ibmi;

import java.util.Objects;

/**
 * A message the IBM i returned with a call: its id and its first-level text. They go to the operator's log only, never to the
 * caller.
 *
 * @param id   the message id, such as {@code CPF9811}
 * @param text the first-level text
 */
public record IbmiMessage(String id, String text) {

    /** @throws NullPointerException if an argument is {@code null} */
    public IbmiMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
    }
}
