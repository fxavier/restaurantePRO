package com.xavier.backend.inventory.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The D42 probe of a transfer under row-level security (D168): its two stores, read through
 * {@code inventory.stock_transfer_stores}, which sees every store, so a third store's actor gets 403, not 404. It
 * reveals only the two store ids, which D42 (b) already reveals.
 */
@Repository
public class TransferStoreProbe {

    private final JdbcClient jdbc;

    TransferStoreProbe(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The two stores of a transfer, or empty when there is no such transfer. */
    public Optional<TransferStores> storesOf(UUID transferId) {
        return jdbc.sql("SELECT from_store_id, to_store_id FROM inventory.stock_transfer_stores(?)")
                .param(transferId)
                .query((rs, n) -> new TransferStores(rs.getObject("from_store_id", UUID.class),
                        rs.getObject("to_store_id", UUID.class))).optional();
    }
}
