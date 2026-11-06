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

    public static final String SALES = """
            SELECT %s AS key, %s AS label, count(DISTINCT l.document_id) AS documents, sum(l.quantity) AS quantity,
                   sum(l.gross) AS gross, sum(l.net) AS net, sum(l.tax) AS tax, max(l.projected_at) AS as_of
            FROM reporting.sales_record_line l %s
            WHERE l.store_id = :store AND l.business_date BETWEEN :from AND :to
            GROUP BY 1 %s""";

    static final String HEADER_JOIN = "JOIN reporting.sales_record s ON s.document_id = l.document_id";
    static final String BY_KEY = "ORDER BY 1 NULLS LAST";

    public static final String TAXES = """
            SELECT tax_code, tax_rate, sum(net) AS net, sum(tax) AS tax, max(projected_at) AS as_of
            FROM reporting.sales_record_tax
            WHERE store_id = :store AND business_date BETWEEN :from AND :to
            GROUP BY tax_code, tax_rate ORDER BY tax_rate, tax_code""";

    public static final String VOIDS = """
            SELECT %s AS key, %s AS label, count(*) AS voids, sum(quantity) AS quantity, sum(gross) AS gross,
                   count(*) FILTER (WHERE wasted) AS wasted, count(*) FILTER (WHERE after_dispatch) AS after_dispatch,
                   max(projected_at) AS as_of
            FROM reporting.void_record
            WHERE store_id = :store AND business_date BETWEEN :from AND :to
            GROUP BY 1 ORDER BY 1 NULLS LAST""";

    private final JdbcClient jdbc;

    ReportQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SalesAggregate(String key, String label, long documents, BigDecimal quantity, BigDecimal gross,
                                 BigDecimal net, BigDecimal tax, Instant asOf) {
    }

    public record TaxAggregate(String taxCode, BigDecimal rate, BigDecimal net, BigDecimal tax, Instant asOf) {
    }

    public record VoidsAggregate(String key, String label, long voids, BigDecimal quantity, BigDecimal gross,
                                 long wasted, long afterDispatch, Instant asOf) {
    }

    public record ReceiptsAggregate(String key, BigDecimal received, BigDecimal debtCollected, Instant asOf) {
    }

    public record CreditAggregate(String key, BigDecimal onAccount, Instant asOf) {
    }

    /**
     * Sales lines grouped by {@code keyExpression} (labelled by {@code labelExpression}), joined to their document
     * header when {@code withHeader}, in key order; {@link #TOTAL} gives the totals.
     */
    public List<SalesAggregate> sales(String keyExpression, String labelExpression, boolean withHeader, UUID store,
                                      LocalDate from, LocalDate to) {
        return sales(SALES.formatted(keyExpression, labelExpression, withHeader ? HEADER_JOIN : "", BY_KEY), store,
                from, to).list();
    }

    /** The {@code limit} items with the greatest quantity (or gross, when {@code byGross}), then by item code. */
    public List<SalesAggregate> topItems(String keyExpression, String labelExpression, boolean byGross, int limit,
                                         UUID store, LocalDate from, LocalDate to) {
        String order = "ORDER BY sum(l.%s) DESC, 1 NULLS LAST LIMIT :limit".formatted(byGross ? "gross" : "quantity");
        return jdbc.sql(SALES.formatted(keyExpression, labelExpression, "", order)).param("store", store)
                .param("from", from).param("to", to).param("limit", limit).query(ReportQueries::sales).list();
    }

    public List<TaxAggregate> taxes(UUID store, LocalDate from, LocalDate to) {
        return query(TAXES, store, from, to)
                .query((rs, n) -> new TaxAggregate(rs.getString("tax_code"), rs.getBigDecimal("tax_rate"),
                        rs.getBigDecimal("net"), rs.getBigDecimal("tax"), instant(rs, "as_of")))
                .list();
    }

    private JdbcClient.MappedQuerySpec<SalesAggregate> sales(String sql, UUID store, LocalDate from, LocalDate to) {
        return query(sql, store, from, to).query(ReportQueries::sales);
    }

    private static SalesAggregate sales(ResultSet rs, int row) throws SQLException {
        return new SalesAggregate(rs.getString("key"), rs.getString("label"), rs.getLong("documents"),
                rs.getBigDecimal("quantity"), rs.getBigDecimal("gross"), rs.getBigDecimal("net"),
                rs.getBigDecimal("tax"), instant(rs, "as_of"));
    }

    /** Voids grouped by {@code keyExpression} (labelled by {@code labelExpression}), or {@link #TOTAL}. */
    public List<VoidsAggregate> voids(String keyExpression, String labelExpression, UUID store, LocalDate from,
                                      LocalDate to) {
        return query(VOIDS.formatted(keyExpression, labelExpression), store, from, to)
                .query((rs, n) -> new VoidsAggregate(rs.getString("key"), rs.getString("label"), rs.getLong("voids"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("gross"), rs.getLong("wasted"),
                        rs.getLong("after_dispatch"), instant(rs, "as_of")))
                .list();
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

    /** The newest projection of the store, over every projection table; {@code null} before the first. */
    public Instant lastProjectedAt(UUID store) {
        return jdbc.sql("""
                        SELECT greatest(
                            (SELECT max(projected_at) FROM reporting.sales_record WHERE store_id = :store),
                            (SELECT max(projected_at) FROM reporting.payment_record WHERE store_id = :store),
                            (SELECT max(projected_at) FROM reporting.credit_record WHERE store_id = :store),
                            (SELECT max(projected_at) FROM reporting.void_record WHERE store_id = :store)) AS as_of""")
                .param("store", store).query((rs, n) -> instant(rs, "as_of")).list().getFirst();
    }

    private JdbcClient.StatementSpec query(String sql, UUID store, LocalDate from, LocalDate to) {
        return jdbc.sql(sql).param("store", store).param("from", from).param("to", to);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
