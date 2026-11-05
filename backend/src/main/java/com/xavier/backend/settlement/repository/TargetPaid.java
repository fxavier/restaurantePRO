package com.xavier.backend.settlement.repository;

import com.xavier.backend.settlement.model.TargetType;
import java.math.BigDecimal;
import java.util.UUID;

/** Σ of an order's payments to one target: the ORDER ({@code partitionId == null}) or one of its partitions. */
public record TargetPaid(TargetType targetType, UUID partitionId, BigDecimal paid) {
}
