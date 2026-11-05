package com.xavier.backend.settlement.model;

/**
 * What a payment attempt, a payment or an allocation pays (D69): the {@code ORDER} (its residual once it has
 * partitions), one of its bill {@code PARTITION}s, or a customer's {@code CREDIT_ACCOUNT} (S29). R2 rows are
 * {@code ORDER}. The target key is the partition, else the order, else the credit account.
 */
public enum TargetType {
    ORDER,
    PARTITION,
    CREDIT_ACCOUNT
}
