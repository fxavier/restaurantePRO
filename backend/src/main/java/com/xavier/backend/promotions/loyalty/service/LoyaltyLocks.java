package com.xavier.backend.promotions.loyalty.service;

import com.xavier.backend.promotions.loyalty.model.LoyaltyAccount;
import com.xavier.backend.promotions.loyalty.repository.LoyaltyAccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The loyalty account row lock (D112, D132: after {@code order_header} and {@code credit_account}, before cash and
 * fiscal). It always decides on the locked row: the account is refreshed under the lock, so an instance this
 * transaction already holds never hands back its old state.
 */
@Component
class LoyaltyLocks {

    private final EntityManager entityManager;
    private final LoyaltyAccountRepository accounts;

    LoyaltyLocks(EntityManager entityManager, LoyaltyAccountRepository accounts) {
        this.entityManager = entityManager;
        this.accounts = accounts;
    }

    /** {@code SELECT … FOR UPDATE} on the member's account until the transaction ends; empty when not enrolled. */
    Optional<LoyaltyAccount> account(UUID customerId) {
        return accounts.findById(customerId).map(account -> {
            entityManager.refresh(account, LockModeType.PESSIMISTIC_WRITE);
            return account;
        });
    }
}
