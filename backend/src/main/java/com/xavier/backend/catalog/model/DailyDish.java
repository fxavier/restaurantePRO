package com.xavier.backend.catalog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One daily dish of a store for a business date, at its position (D109). Operational, outside the release; the
 * day's list is replaced as a whole, so rows are written by the repository and read-only here.
 */
@Entity
@Immutable
@Table(schema = "catalog", name = "daily_dish")
public class DailyDish {

    @Embeddable
    public record Key(UUID storeId, LocalDate businessDate, UUID itemId) {
    }

    @EmbeddedId
    private Key id;

    @Column(nullable = false)
    private int position;

    protected DailyDish() {
        // JPA
    }

    public UUID getItemId() {
        return id.itemId();
    }

    public int getPosition() {
        return position;
    }
}
