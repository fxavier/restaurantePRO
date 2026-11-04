package com.xavier.backend.cash.model.dto;

import com.xavier.backend.shared.Money;

/** An amount per payment method, e.g. what a session should hold (Σ IN − Σ OUT). */
public record MethodAmount(String methodCode, Money amount) {
}
