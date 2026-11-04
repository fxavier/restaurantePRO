package com.xavier.backend.cash.repository;

import java.math.BigDecimal;

/** A per-method sum read by the cash queries (expected or counted amount of a method). */
public record MethodTotal(String methodCode, BigDecimal amount) {
}
