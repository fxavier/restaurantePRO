package com.xavier.backend.production.model;

import com.xavier.backend.production.model.dto.DestinationRouteView;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** A production destination of a store routed to one of its printers (D56). */
@Entity
@Table(schema = "production", name = "destination_route")
public class DestinationRoute {

    @EmbeddedId
    private Key key;

    @Column(nullable = false)
    private UUID printerId;

    protected DestinationRoute() {
        // JPA
    }

    public DestinationRoute(UUID storeId, String destinationCode, UUID printerId) {
        this.key = new Key(storeId, destinationCode.strip());
        this.printerId = Objects.requireNonNull(printerId, "printerId");
    }

    public UUID getPrinterId() {
        return printerId;
    }

    public DestinationRouteView toView() {
        return new DestinationRouteView(key.destinationCode, printerId);
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "store_id", nullable = false)
        private UUID storeId;

        @Column(name = "destination_code", nullable = false, length = 40)
        private String destinationCode;

        protected Key() {
            // JPA
        }

        public Key(UUID storeId, String destinationCode) {
            this.storeId = Objects.requireNonNull(storeId, "storeId");
            this.destinationCode = Objects.requireNonNull(destinationCode, "destinationCode");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && storeId.equals(key.storeId)
                    && destinationCode.equals(key.destinationCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(storeId, destinationCode);
        }
    }
}
