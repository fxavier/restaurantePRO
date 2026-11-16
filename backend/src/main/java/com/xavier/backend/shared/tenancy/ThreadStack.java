package com.xavier.backend.shared.tenancy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * A per-thread stack of explicit bindings (the tenant of {@link TenantScopes}, the store scope of
 * {@code access.StoreScopes}): the innermost one wins, each ends with the call that pushed it, and an emptied stack
 * leaves nothing on the thread.
 */
public final class ThreadStack<T> {

    private final ThreadLocal<Deque<T>> stack = ThreadLocal.withInitial(ArrayDeque::new);

    /** The innermost binding of this thread, or {@code null}. */
    public T peek() {
        return stack.get().peek();
    }

    /** {@code action} with {@code binding} innermost; removed after, whatever {@code action} does. */
    public <R> R within(T binding, Supplier<R> action) {
        Deque<T> bindings = stack.get();
        bindings.push(binding);
        try {
            return action.get();
        } finally {
            bindings.pop();
            if (bindings.isEmpty()) {
                stack.remove();
            }
        }
    }
}
