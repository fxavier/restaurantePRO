package com.xavier.backend.customers.repository;

import java.math.BigDecimal;
import java.util.UUID;

/** One row of the debts by age ({@link CreditLedgerEntryRepository#debts}). */
public interface DebtAging {

    UUID getCustomerId();

    String getName();

    String getTaxId();

    String getCurrency();

    BigDecimal getBalance();

    BigDecimal getDays0To30();

    BigDecimal getDays31To60();

    BigDecimal getDays61To90();

    BigDecimal getOver90();
}
