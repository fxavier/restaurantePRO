package com.xavier.backend.orders.repository;

/**
 * The one walk up a line's ancestry through its transfer and split facts (D105, D112a), shared by every query that
 * asks where a line comes from: {@code origin(start_id, line_id, bound, depth)} holds, for each of {@code :lineIds},
 * the line itself (depth 0, no bound) and each ancestor, with {@code bound} the instant the quantity moved away from
 * it (what an ancestor did after that is not the copy's).
 */
final class LineAncestry {

    static final String ORIGIN = """
            WITH RECURSIVE origin (start_id, line_id, bound, depth) AS (
                SELECT l.id, l.id, CAST(NULL AS TIMESTAMP WITH TIME ZONE), 0
                FROM orders.order_line l WHERE l.id IN (:lineIds)
                UNION ALL
                SELECT o.start_id, e.source_line_id, e.at, o.depth + 1
                FROM origin o
                JOIN (SELECT tl.target_line_id, tl.source_line_id, t.transferred_at AS at
                      FROM orders.order_transfer_line tl JOIN orders.order_transfer t ON t.id = tl.transfer_id
                      UNION ALL
                      SELECT s.target_line_id, s.source_line_id, s.at FROM orders.order_line_split s) e
                    ON e.target_line_id = o.line_id)
            """;

    private LineAncestry() {
    }
}
