package com.xavier.backend.dining.service;

import com.xavier.backend.dining.exception.DiningNotFoundException;
import com.xavier.backend.dining.model.Reservation;
import com.xavier.backend.dining.model.RestaurantTable;
import com.xavier.backend.dining.model.RoomArea;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Row locks that also re-read the row. A locking JPQL query on an entity already managed in the transaction (for
 * example after {@code DiningApi.tableState}) takes the lock but returns the stale instance, so a deactivation or a
 * lock committed in between would be missed. {@code refresh} with the lock mode reads the locked row into it.
 *
 * <p>Lock order, always: table, then area. An opening locks its table and shares its area; a deactivation locks
 * the row it changes.
 */
@Component
public class DiningRows {

    private final EntityManager entityManager;

    DiningRows(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** {@code SELECT ... FOR UPDATE}: serializes openings, locks and deactivation of one table. */
    public RestaurantTable lockTable(UUID id) {
        return locked(RestaurantTable.class, "RestaurantTable", id, LockModeType.PESSIMISTIC_WRITE);
    }

    /** {@code SELECT ... FOR UPDATE}: a deactivation waits for running openings in the area, then sees them. */
    public RoomArea lockArea(UUID id) {
        return locked(RoomArea.class, "RoomArea", id, LockModeType.PESSIMISTIC_WRITE);
    }

    /** {@code SELECT ... FOR UPDATE}: serializes the commands on one reservation, the seating included. */
    public Reservation lockReservation(UUID id) {
        return locked(Reservation.class, "Reservation", id, LockModeType.PESSIMISTIC_WRITE);
    }

    /** {@code SELECT ... FOR SHARE}: an opening keeps its area from being deactivated until it commits. */
    public RoomArea shareArea(UUID id) {
        return locked(RoomArea.class, "RoomArea", id, LockModeType.PESSIMISTIC_READ);
    }

    private <T> T locked(Class<T> type, String entityType, UUID id, LockModeType mode) {
        T entity = entityManager.find(type, id);
        if (entity == null) {
            throw new DiningNotFoundException(entityType, id);
        }
        entityManager.refresh(entity, mode);
        return entity;
    }
}
