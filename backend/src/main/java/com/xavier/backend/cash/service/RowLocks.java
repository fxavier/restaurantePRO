package com.xavier.backend.cash.service;

import com.xavier.backend.cash.exception.CashNotFoundException;
import com.xavier.backend.cash.model.CashDay;
import com.xavier.backend.cash.model.CashSession;
import com.xavier.backend.cash.repository.CashDayRepository;
import com.xavier.backend.cash.repository.CashSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Short row locks on sessions and days (D51) that always decide on the locked row. A locking query would hand back
 * an instance this transaction already holds (e.g. settlement called {@code openSessionOf} first) with its old
 * state, so a concurrent close would go unseen; refreshing under the lock re-reads the row once it is ours.
 */
@Component
class RowLocks {

    private final EntityManager entityManager;
    private final CashSessionRepository sessions;
    private final CashDayRepository days;

    RowLocks(EntityManager entityManager, CashSessionRepository sessions, CashDayRepository days) {
        this.entityManager = entityManager;
        this.sessions = sessions;
        this.days = days;
    }

    /** {@code FOR SHARE}: movements into the session wait for, and then see, a concurrent close. */
    CashSession sessionForShare(UUID id) {
        return lock(sessions.findById(id).orElseThrow(() -> notFound(CashSessionService.ENTITY, id)),
                LockModeType.PESSIMISTIC_READ);
    }

    /** {@code FOR UPDATE}: the session close serializes with receipts and movements into it. */
    CashSession sessionForUpdate(UUID id) {
        return lock(sessions.findById(id).orElseThrow(() -> notFound(CashSessionService.ENTITY, id)),
                LockModeType.PESSIMISTIC_WRITE);
    }

    CashDay dayForUpdate(UUID id) {
        return lock(days.findById(id).orElseThrow(() -> notFound(CashDayService.ENTITY, id)),
                LockModeType.PESSIMISTIC_WRITE);
    }

    <T> T lock(T entity, LockModeType mode) {
        entityManager.refresh(entity, mode);
        return entity;
    }

    private static CashNotFoundException notFound(String entityType, UUID id) {
        return new CashNotFoundException(entityType, id);
    }
}
