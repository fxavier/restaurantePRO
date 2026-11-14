package com.xavier.backend.shared.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The D42 probe under row-level security (D168): the store of a resource, read through {@code shared.store_of}, which
 * sees every store, so another store's resource is still found (then 403), never mistaken for absent (404). It reveals
 * only the {@code store_id}, which D42 (b) already reveals. Module probe methods switch to it in their policy slice.
 */
@Component
public class StoreProbe {

    private final JdbcClient jdbc;

    public StoreProbe(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param table a protected table with a {@code store_id} and a single-column uuid primary key, e.g.
     *              {@code orders.order_header}; anything else is refused by the database
     * @return the row's store, or empty when there is no such row
     */
    public Optional<UUID> storeOf(String table, UUID id) {
        return jdbc.sql("SELECT shared.store_of(CAST(:t AS regclass), :id)").param("t", table).param("id", id)
                .query(UUID.class).optional();
    }
}
