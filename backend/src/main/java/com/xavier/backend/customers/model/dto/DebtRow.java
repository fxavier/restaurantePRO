package com.xavier.backend.customers.model.dto;

import com.xavier.backend.shared.Money;
import java.util.UUID;

/** A customer's open debt as of a date, split by the age of each open debit in days. */
public record DebtRow(UUID customerId, String name, String taxId, Money balance, Money days0To30, Money days31To60,
                      Money days61To90, Money over90) {
}
