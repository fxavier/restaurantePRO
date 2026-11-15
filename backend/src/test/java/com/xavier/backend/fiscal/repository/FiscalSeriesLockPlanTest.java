package com.xavier.backend.fiscal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.fiscal.model.DocumentType;
import com.xavier.backend.settlement.AppRoleSession;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * D171 (S67), A77: the series lock of every issue ({@link FiscalSeriesRepository#lockActive}, {@code FOR UPDATE},
 * gapless numbering) stays on the partial index {@code fiscal_series_active_uk} under row-level security, as the
 * application role in a store scope ({@code booleq}, {@code texteq}, {@code uuid_eq} are leakproof, D169), so the lock
 * takes the one active row, not a scan. Deterministic: 2 000 inactive series and one active in one store, ANALYZE by
 * the owner, then EXPLAIN in one transaction with {@code enable_seqscan = off}.
 *
 * <p>D169 audit of fiscal: no query on a protected table relies on an index for a {@code numeric}, {@code jsonb} or
 * {@code LIKE} predicate ({@code number} is a {@code bigint}).
 */
class FiscalSeriesLockPlanTest {

    /** The SQL of {@code lockActive}, translated by hand; kept in step by the JPQL test below. */
    static final String LOCK_ACTIVE = """
            SELECT s.* FROM fiscal.fiscal_series s
            WHERE s.store_id = ? AND s.document_type = 'SALE' AND s.active = true FOR UPDATE""";

    @Test
    void theActiveSeriesIsLockedThroughThePartialIndexUnderAStoreScope() {
        UUID store = UUID.randomUUID();
        seed(store);

        try (AppRoleSession app = AppRoleSession.open()) {
            app.stores(store);
            List<String> plan = new ArrayList<>();
            List<Object> locked = new ArrayList<>();
            app.inTransaction(() -> {
                app.jdbc().sql("SET LOCAL enable_seqscan = off").update();
                plan.addAll(app.jdbc().sql("EXPLAIN " + LOCK_ACTIVE).param(store).query(String.class).list());
                locked.addAll(app.jdbc().sql(LOCK_ACTIVE).param(store).query().listOfRows());
            });

            assertThat(String.join("\n", plan)).contains("LockRows").containsPattern(
                    "(Index Scan using|Bitmap Index Scan on) fiscal_series_active_uk");
            assertThat(locked).hasSize(1);
        }
    }

    @Test
    void theRepositoryQueryKeepsThePartialIndexPredicate() throws Exception {
        String jpql = FiscalSeriesRepository.class.getMethod("lockActive", UUID.class, DocumentType.class)
                .getAnnotation(Query.class).value().replaceAll("\\s+", " ");

        assertThat(jpql).isEqualTo(
                "select s from FiscalSeries s where s.storeId = :storeId and s.documentType = :type and s.active = true");
    }

    private static void seed(UUID store) {
        OwnerDatabase.shared().jdbc().sql("""
                INSERT INTO fiscal.fiscal_series (id, store_id, document_type, code, first_number, next_number, active,
                    version, created_at, updated_at)
                SELECT gen_random_uuid(), :store, 'SALE', 'P' || n, 1, 1, n = 0, 0, now(), now()
                FROM generate_series(0, 2000) AS n""").param("store", store).update();
        OwnerDatabase.shared().jdbc().sql("ANALYZE fiscal.fiscal_series").update();
    }
}
