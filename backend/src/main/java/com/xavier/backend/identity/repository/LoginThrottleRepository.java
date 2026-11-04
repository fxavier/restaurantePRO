package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.LoginThrottle;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginThrottleRepository extends JpaRepository<LoginThrottle, String> {

    /**
     * Counts one attempt atomically (the upsert locks the row): a window that started at or before
     * {@code windowFloor} starts again at 1; the {@code limit}-th attempt in a window sets {@code blocked_until}.
     * A blocked row is left as it is.
     *
     * @return the attempts in the window including this one, or empty when the key is blocked at {@code now}
     */
    @Query(nativeQuery = true, value = """
            INSERT INTO identity.login_throttle AS t (key, failures, window_start, blocked_until)
            VALUES (:key, 1, :now, NULL)
            ON CONFLICT (key) DO UPDATE SET
                failures      = CASE WHEN t.window_start <= :windowFloor THEN 1 ELSE t.failures + 1 END,
                window_start  = CASE WHEN t.window_start <= :windowFloor
                                     THEN CAST(:now AS TIMESTAMP WITH TIME ZONE) ELSE t.window_start END,
                blocked_until = CASE WHEN t.window_start > :windowFloor AND t.failures + 1 >= :limit
                                     THEN CAST(:blockedUntil AS TIMESTAMP WITH TIME ZONE) END,
                block_noticed = FALSE
            WHERE t.blocked_until IS NULL OR t.blocked_until <= :now
            RETURNING failures""")
    List<Integer> countAttempt(@Param("key") String key, @Param("now") Instant now,
                               @Param("windowFloor") Instant windowFloor, @Param("limit") int limit,
                               @Param("blockedUntil") Instant blockedUntil);

    /**
     * Marks the block of {@code key} as noticed (its first refused request).
     *
     * @return 1 the first time for a block that is active at {@code now}, else 0
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE identity.login_throttle SET block_noticed = TRUE
             WHERE key = :key AND blocked_until > :now AND NOT block_noticed""")
    int noticeBlock(@Param("key") String key, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM LoginThrottle t WHERE t.windowStart < :before")
    int deleteWindowsStartedBefore(@Param("before") Instant before);
}
