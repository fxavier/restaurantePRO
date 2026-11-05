package com.xavier.backend.customers.service;

import com.xavier.backend.customers.CreditView;
import com.xavier.backend.customers.model.CreditAccount;
import com.xavier.backend.customers.repository.CreditLedgerEntryRepository;
import com.xavier.backend.customers.repository.CreditReservationRepository;
import com.xavier.backend.shared.Money;
import java.util.Currency;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The derived amounts of a credit account (D73): one place for balance, reserved and available. */
@Component
class CreditBalances {

    private final CreditLedgerEntryRepository entries;
    private final CreditReservationRepository reservations;

    CreditBalances(CreditLedgerEntryRepository entries, CreditReservationRepository reservations) {
        this.entries = entries;
        this.reservations = reservations;
    }

    Money balance(UUID customerId, Currency currency) {
        return Money.of(entries.balance(customerId), currency);
    }

    CreditView view(CreditAccount account) {
        Money balance = balance(account.getCustomerId(), account.currency());
        Money reserved = Money.of(reservations.sumActive(account.getCustomerId()), account.currency());
        return new CreditView(account.limit(), balance, reserved, account.limit().minus(balance).minus(reserved),
                account.isCreditBlocked());
    }
}
