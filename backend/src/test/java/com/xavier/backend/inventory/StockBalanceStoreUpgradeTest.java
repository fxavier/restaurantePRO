package com.xavier.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.TestcontainersConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V13080 on a live R5 installation (D167): balance rows and an in-transit transfer written by the last release, before
 * the migration, get their warehouse's store and their shipped value; afterwards a balance can never name another
 * store than its warehouse's. In its own database of the shared container, installed with the released migrations
 * ({@code db/released-migrations.txt}) and upgraded with the current ones, out of order as in production. After
 * V13080 the R5 image cannot write balances (no {@code store_id}): a rollback to R5 then needs a restore (S65).
 */
class StockBalanceStoreUpgradeTest {

    private static final Pattern VERSION = Pattern.compile("V(\\d+)__.+\\.sql");
    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-11-01T10:00:00Z"));

    private final PostgreSQLContainer postgres = TestcontainersConfiguration.postgres();
    private final String database = "inventory_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcClient admin;
    private DataSource installation;
    private JdbcClient jdbc;

    @TempDir
    Path released;

    @TempDir
    Path current;

    @BeforeEach
    void createDatabase() {
        admin = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
        admin.sql("CREATE DATABASE " + database).update();
        installation = new DriverManagerDataSource(postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName()
                + "?", "/" + database + "?"), postgres.getUsername(), postgres.getPassword());
        jdbc = JdbcClient.create(installation);
    }

    @AfterEach
    void dropDatabase() {
        admin.sql("DROP DATABASE " + database + " WITH (FORCE)").update();
    }

    @Test
    void rowsWrittenBeforeTheMigrationGetTheirWarehousesStoreAndShippedValue() throws IOException {
        copyMigrations();
        flyway(released).migrate();
        UUID storeA = UUID.randomUUID();
        UUID storeB = UUID.randomUUID();
        UUID item = stockItem();
        UUID warehouseA = warehouse(storeA);
        UUID warehouseB = warehouse(storeB);
        balance(warehouseA, item, "7", "14");
        balance(warehouseB, item, "3", "6");
        UUID inTransit = shippedTransfer(warehouseA, storeA, warehouseB, storeB, item, "-4.0001");
        UUID draft = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO inventory.stock_transfer (id, from_warehouse_id, to_warehouse_id, from_store_id,
                            to_store_id, status, created_at, version) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, 0)""")
                .params(draft, warehouseA, warehouseB, storeA, storeB, AT).update();
        jdbc.sql("INSERT INTO inventory.stock_transfer_line (transfer_id, stock_item_id, quantity_shipped) "
                + "VALUES (?, ?, 1)").params(draft, item).update();

        flyway(current).migrate();

        assertThat(jdbc.sql("SELECT store_id FROM inventory.stock_balance WHERE warehouse_id = ?")
                .param(warehouseA).query(UUID.class).single()).isEqualTo(storeA);
        assertThat(jdbc.sql("SELECT store_id FROM inventory.stock_balance WHERE warehouse_id = ?")
                .param(warehouseB).query(UUID.class).single()).isEqualTo(storeB);
        assertThat(jdbc.sql("SELECT quantity || '|' || value FROM inventory.stock_balance WHERE warehouse_id = ?")
                .param(warehouseA).query(String.class).single()).as("nothing else changed")
                .isEqualTo("7.000000|14.0000");
        assertThat(jdbc.sql("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'inventory' "
                + "AND table_name = 'stock_balance' AND column_name = 'store_id'").query(String.class).single())
                .isEqualTo("NO");
        assertThat(jdbc.sql("SELECT value_shipped FROM inventory.stock_transfer_line WHERE transfer_id = ?")
                .param(inTransit).query(java.math.BigDecimal.class).single())
                .as("the value its OUT took").isEqualByComparingTo("4.0001");
        assertThat(jdbc.sql("SELECT value_shipped FROM inventory.stock_transfer_line WHERE transfer_id = ?")
                .param(draft).query(java.math.BigDecimal.class).optional()).as("not shipped").isEmpty();
        UUID other = stockItem();
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO inventory.stock_balance (warehouse_id, store_id, stock_item_id, quantity, value,
                            average_cost, last_posting_seq, updated_at) VALUES (?, ?, ?, 0, 0, 0, 0, ?)""")
                .params(warehouseA, storeB, other, AT).update())
                .hasMessageContaining("stock_balance_warehouse_store_fk");
    }

    private UUID stockItem() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO inventory.stock_item (id, code, name, base_unit, kind, active, version, created_at,
                            updated_at) VALUES (?, ?, 'Flour', 'g', 'PURCHASED', true, 0, ?, ?)""")
                .params(id, "UP-" + id, AT, AT).update();
        return id;
    }

    private UUID warehouse(UUID store) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO inventory.warehouse (id, store_id, code, name, active, version, created_at,
                            updated_at) VALUES (?, ?, 'MAIN', 'Main', true, 0, ?, ?)""").params(id, store, AT, AT)
                .update();
        return id;
    }

    private void balance(UUID warehouse, UUID item, String quantity, String value) {
        jdbc.sql("""
                        INSERT INTO inventory.stock_balance (warehouse_id, stock_item_id, quantity, value, average_cost,
                            last_posting_seq, updated_at) VALUES (?, ?, CAST(? AS numeric), CAST(? AS numeric), 2, 1, ?)""")
                .params(warehouse, item, quantity, value, AT).update();
    }

    /** A transfer the R5 image shipped: its line and the TRANSFER_OUT that took {@code outValue}. */
    private UUID shippedTransfer(UUID from, UUID fromStore, UUID to, UUID toStore, UUID item, String outValue) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO inventory.stock_transfer (id, from_warehouse_id, to_warehouse_id, from_store_id,
                            to_store_id, status, created_at, shipped_at, version)
                        VALUES (?, ?, ?, ?, ?, 'SHIPPED', ?, ?, 1)""")
                .params(id, from, to, fromStore, toStore, AT, AT).update();
        jdbc.sql("INSERT INTO inventory.stock_transfer_line (transfer_id, stock_item_id, quantity_shipped, unit_cost) "
                + "VALUES (?, ?, 2, 2)").params(id, item).update();
        jdbc.sql("""
                        INSERT INTO inventory.stock_movement (id, warehouse_id, store_id, stock_item_id, type, quantity,
                            unit_cost, value, currency, source_type, source_id, business_date, occurred_at, posted_at,
                            actor_kind)
                        VALUES (?, ?, ?, ?, 'TRANSFER_OUT', -2, 2, CAST(? AS numeric), 'MZN', 'TRANSFER', ?, ?, ?, ?,
                            'SYSTEM')""")
                .params(UUID.randomUUID(), from, fromStore, item, outValue, id, LocalDate.parse("2026-11-01"), AT,
                        AT).update();
        return id;
    }

    private Flyway flyway(Path location) {
        return Flyway.configure().dataSource(installation).locations("filesystem:" + location).outOfOrder(true)
                .load();
    }

    private void copyMigrations() throws IOException {
        Set<Integer> releasedVersions = releasedVersions();
        for (Resource migration : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/**/*.sql")) {
            String name = migration.getFilename();
            try (InputStream in = migration.getInputStream()) {
                Files.write(current.resolve(name), in.readAllBytes());
            }
            if (releasedVersions.contains(version(name))) {
                Files.copy(current.resolve(name), released.resolve(name));
            }
        }
        assertThat(releasedVersions).as("V13080 is new in this release").doesNotContain(13080);
    }

    private static Set<Integer> releasedVersions() throws IOException {
        try (InputStream in = new ClassPathResource("db/released-migrations.txt").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#")).map(Integer::valueOf)
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static int version(String file) {
        Matcher name = VERSION.matcher(file);
        if (!name.matches()) {
            throw new IllegalStateException("not a versioned migration: " + file);
        }
        return Integer.parseInt(name.group(1));
    }
}
