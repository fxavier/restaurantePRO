package com.xavier.backend.fiscal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V10007 on a database that already holds R2 documents (D93: no migration UPDATEs an immutable fact row). The
 * schema is migrated to V10006 in a database of its own, R2-shaped rows are written, and only then V10007 runs: an
 * ISSUED row gets {@code order_ref} from its stored generated column, so the immutability trigger never fires.
 */
class R2DocumentOrderRefTest {

    private final PostgreSQLContainer postgres = TestcontainersConfiguration.postgres();
    private final String database = "fiscal_r2_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcClient admin;
    private DriverManagerDataSource r2;

    @BeforeEach
    void createDatabase() {
        admin = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
        admin.sql("CREATE DATABASE " + database).update();
        r2 = new DriverManagerDataSource(postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName() + "?",
                "/" + database + "?"), postgres.getUsername(), postgres.getPassword());
    }

    @AfterEach
    void dropDatabase() {
        admin.sql("DROP DATABASE " + database + " WITH (FORCE)").update();
    }

    @Test
    void anR2DocumentGetsItsOrderAsOrderRefWithoutAnUpdate() {
        migrateTo("10006");
        JdbcClient jdbc = JdbcClient.create(r2);
        UUID series = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        UUID issuedOrder = UUID.randomUUID();
        UUID draftOrder = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_series (id, store_id, document_type, code, first_number, next_number, active,
                                                  version, created_at, updated_at)
                VALUES (:id, :store, 'SALE', 'A', 1, 2, true, 0, now(), now())""")
                .param("id", series).param("store", store).update();
        UUID issued = insertR2Document(jdbc, store, issuedOrder, series, 1L);
        UUID draft = insertR2Document(jdbc, store, draftOrder, null, null);

        migrateTo("10007");

        Map<String, Object> issuedRow = row(jdbc, issued);
        assertThat(issuedRow.get("order_ref")).isEqualTo(issuedOrder);
        assertThat(issuedRow.get("on_account")).isEqualTo(false);
        assertThat(issuedRow.get("order_id")).isNull();
        assertThat(issuedRow.get("business_date")).isNull();
        assertThat(issuedRow.get("version")).isEqualTo(0L);
        assertThat(row(jdbc, draft).get("order_ref")).isEqualTo(draftOrder);
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(r2).locations("classpath:db/migration").target(version).load().migrate();
    }

    private static UUID insertR2Document(JdbcClient jdbc, UUID store, UUID order, UUID series, Long number) {
        UUID id = UUID.randomUUID();
        boolean issued = series != null;
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_document
                    (id, store_id, document_type, status, source_type, source_id, series_id, series_code, number,
                     currency, total_gross, total_net, total_tax, company_name, company_tax_id, company_country,
                     store_code, store_name, store_timezone, adapter_code, non_fiscal, issued_at, version,
                     created_at, updated_at)
                VALUES (:id, :store, 'SALE', :status, 'ORDER', :order, :series, :code, :number, 'MZN', 116, 100, 16,
                        'Co', 'TAX', 'MZ', 'S1', 'Loja', 'Africa/Maputo', :adapter, :nonFiscal, :issuedAt, 0,
                        now(), now())""")
                .param("id", id).param("store", store).param("status", issued ? "ISSUED" : "DRAFT")
                .param("order", order).param("series", series).param("code", issued ? "A" : null)
                .param("number", number).param("adapter", issued ? "internal" : null)
                .param("nonFiscal", issued ? Boolean.TRUE : null)
                .param("issuedAt", issued ? java.sql.Timestamp.from(java.time.Instant.now()) : null)
                .update();
        return id;
    }

    private static Map<String, Object> row(JdbcClient jdbc, UUID id) {
        return jdbc.sql("SELECT * FROM fiscal.fiscal_document WHERE id = :id").param("id", id).query().singleRow();
    }
}
