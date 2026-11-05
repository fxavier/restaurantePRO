package com.xavier.backend.customers.model;

/** What a ledger entry records: a credit sale (DEBIT), a debt payment (CREDIT) or its refund (DEBIT). */
public enum EntrySource { CREDIT_CHARGE, PAYMENT, REFUND }
