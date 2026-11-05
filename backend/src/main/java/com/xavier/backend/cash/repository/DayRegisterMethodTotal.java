package com.xavier.backend.cash.repository;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per cash day, register and method: what the movements say the register should hold (Σ IN − Σ OUT) and the part of
 * it that adjustments moved (D79 (7)).
 */
public record DayRegisterMethodTotal(UUID cashDayId, UUID registerId, String methodCode, BigDecimal expected,
                                     BigDecimal adjustments) {
}
