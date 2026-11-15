package com.xavier.backend.shared.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The D42 probe under row-level security (D168): the store of a resource, read through {@code shared.store_of}, which
 * sees every store, so another store's resource is still found (then 403), never mistaken for absent (404). It reveals
 * only the {@code store_id}, which D42 (b) already reveals. Module probe methods switch to it in their policy slice.
 * R6 (D179): only a row of the bound tenant is found; another tenant's row reads as absent (404), and with no tenant
 * bound the probe raises {@code RS003}. Called outside a transaction (the non-transactional {@code authorize} phase of
 * an approved command, D40), it opens a short read-only one, so the tenant and the scope are bound (D178).
 */
@Component
public class StoreProbe {

    private final JdbcClient jdbc;
    private final TransactionOperations transactions;

    /** On a connection the caller has already bound (a test seam): no transaction of its own. */
    public StoreProbe(JdbcClient jdbc) {
        this(jdbc, TransactionOperations.withoutTransaction());
    }

    @Autowired
    public StoreProbe(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, readOnly(transactionManager));
    }

    private StoreProbe(JdbcClient jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    private static TransactionOperations readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    /**
     * @param table a protected table with a {@code store_id} and a single-column uuid primary key, e.g.
     *              {@code orders.order_header}; anything else is refused by the database
     * @return the row's store, or empty when there is no such row
     */
    public Optional<UUID> storeOf(String table, UUID id) {
        return transactions.execute(status -> jdbc.sql("SELECT shared.store_of(CAST(:t AS regclass), :id)")
                .param("t", table).param("id", id).query(UUID.class).optional());
    }
}
