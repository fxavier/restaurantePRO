package com.xavier.backend.cash;

import com.xavier.backend.shared.Money;
import java.util.Objects;

/** The blind count of one payment method at session close; {@code methodCode} is settlement's method code (D52). */
public record MethodCount(String methodCode, Money amount) {

    public MethodCount {
        Objects.requireNonNull(methodCode, "methodCode");
        Objects.requireNonNull(amount, "amount");
    }
}
