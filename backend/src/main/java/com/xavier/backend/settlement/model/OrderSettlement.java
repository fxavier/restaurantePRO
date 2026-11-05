package com.xavier.backend.settlement.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The settlement lock row of an order (D51). It stores no balance (spec §8.3: the financial state is derived); taking
 * it {@code FOR UPDATE} serializes the payments, the document issue and the close of one order. Created on first use.
 */
@Entity
@Table(schema = "settlement", name = "order_settlement")
public class OrderSettlement {

    @Id
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderSettlement() {
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
