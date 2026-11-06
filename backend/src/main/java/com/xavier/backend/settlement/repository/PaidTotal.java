package com.xavier.backend.settlement.repository;

import java.math.BigDecimal;
import java.util.UUID;

/** Σ of the payments of one order. */
public record PaidTotal(UUID orderId, BigDecimal paid) {
}
