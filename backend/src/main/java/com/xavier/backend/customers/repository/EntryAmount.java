package com.xavier.backend.customers.repository;

import java.math.BigDecimal;
import java.util.UUID;

/** A ledger entry and an amount derived for it (its open amount, or what a credit settled on it). */
public interface EntryAmount {

    UUID getEntryId();

    BigDecimal getAmount();
}
