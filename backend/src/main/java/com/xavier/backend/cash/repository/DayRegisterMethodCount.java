package com.xavier.backend.cash.repository;

import java.math.BigDecimal;
import java.util.UUID;

/** Per cash day, register and method: Σ of the blind counts of the register's sessions that day. */
public record DayRegisterMethodCount(UUID cashDayId, UUID registerId, String methodCode, BigDecimal counted) {
}
