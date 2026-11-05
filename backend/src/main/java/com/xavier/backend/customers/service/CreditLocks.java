package com.xavier.backend.customers.service;

import com.xavier.backend.customers.model.CreditAccount;
import com.xavier.backend.customers.repository.CreditAccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The credit account row lock (D73, D76: after {@code order_settlement}, before cash and fiscal). It always decides
 * on the locked row: a locking query would hand back an instance this transaction already holds with its old state,
 * so the account is refreshed under the lock.
 */
@Component
class CreditLocks {

    private final EntityManager entityManager;
    private final CreditAccountRepository accounts;

    CreditLocks(EntityManager entityManager, CreditAccountRepository accounts) {
        this.entityManager = entityManager;
        this.accounts = accounts;
    }

    /** {@code SELECT … FOR UPDATE} on the customer's account until the transaction ends; empty when it has none. */
    Optional<CreditAccount> account(UUID customerId) {
        return accounts.findById(customerId).map(account -> {
            entityManager.refresh(account, LockModeType.PESSIMISTIC_WRITE);
            return account;
        });
    }
}
