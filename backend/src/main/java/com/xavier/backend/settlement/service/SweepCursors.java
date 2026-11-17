package com.xavier.backend.settlement.service;

import com.xavier.backend.settlement.repository.Keyed;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a sweeper's last page of a tenant's queue ended (keyset: {@code at}, then {@code id}). A capped, oldest-first
 * sweep would otherwise start from the same {@code cap} items every run and starve newer ones when that many stay
 * eligible; the next run continues after the last item seen, and a short page means the queue is exhausted, so the
 * cursor resets. Every eligible item is therefore visited within ceil(n / cap) + 1 runs.
 */
final class SweepCursors {

    static final Keyed START = new Keyed(new UUID(0, 0), Instant.EPOCH);

    private final Map<String, Keyed> last = new ConcurrentHashMap<>();

    /** The position to read after, for {@code queue} of {@code tenant}. */
    Keyed after(UUID tenant, String queue) {
        return last.getOrDefault(tenant + ":" + queue, START);
    }

    /** Records that {@code page} (of at most {@code cap}) was read; a short page resets the cursor. */
    void read(UUID tenant, String queue, List<Keyed> page, int cap) {
        String key = tenant + ":" + queue;
        if (page.size() < cap) {
            last.remove(key);
        } else {
            last.put(key, page.getLast());
        }
    }
}
