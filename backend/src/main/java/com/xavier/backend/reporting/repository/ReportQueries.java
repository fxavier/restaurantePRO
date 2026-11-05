package com.xavier.backend.reporting.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The report aggregates over the projections. Every query filters by {@code store_id} and a {@code business_date}
 * range (D85: never a UTC range), which the {@code (store_id, business_date, …)} indexes serve (D86). A grouping
 * column comes from a closed set of expressions, never from the request.
 */
@Repository
public class ReportQueries {

    /** Stands for "no group": the totals of the range. */
    public static final String TOTAL = "NULL::text";

    public static final String RECEIPTS = """
            SELECT %s AS key, sum(signed_amount) AS received,
                   coalesce(sum(signed_amount) FILTER (WHERE target_type = 'CREDIT_ACCOUNT'), 0) AS debt_collected,
                   max(projected_at) AS as_of
            FROM reporting.payment_record
            WHERE store_id = :store AND business_date BETWEEN :from AND :to
            GROUP BY 1 ORDER BY 1 NULLS LAST""";

    public static final String CREDIT = """
            SELECT %s AS key, sum(amount) AS on_account, max(projected_at) AS as_of
            FROM reporting.credit_record
            WHERE store_id = :store AND business_date BETWEEN :from AND :to
            GROUP BY 1 ORDER BY 1 NULLS LAST""";

    private final JdbcClient jdbc;

    ReportQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ReceiptsAggregate(String key, BigDecimal received, BigDecimal debtCollected, Instant asOf) {
    }

    public record CreditAggregate(String key, BigDecimal onAccount, Instant asOf) {
    }

    /** {@code keyExpression} is one of the receipts groupings over {@code payment_record}, or {@link #TOTAL}. */
    public List<ReceiptsAggregate> receipts(String keyExpression, UUID store, LocalDate from, LocalDate to) {
        return query(RECEIPTS.formatted(keyExpression), store, from, to)
                .query((rs, n) -> new ReceiptsAggregate(rs.getString("key"), rs.getBigDecimal("received"),
                        rs.getBigDecimal("debt_collected"), instant(rs, "as_of")))
                .list();
    }

    /** {@code keyExpression} is {@code business_date::text} or {@link #TOTAL}. */
    public List<CreditAggregate> credit(String keyExpression, UUID store, LocalDate from, LocalDate to) {
        return query(CREDIT.formatted(keyExpression), store, from, to)
                .query((rs, n) -> new CreditAggregate(rs.getString("key"), rs.getBigDecimal("on_account"),
                        instant(rs, "as_of")))
                .list();
    }

    private JdbcClient.StatementSpec query(String sql, UUID store, LocalDate from, LocalDate to) {
        return jdbc.sql(sql).param("store", store).param("from", from).param("to", to);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
