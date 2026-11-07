package com.xavier.backend.orders;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** A confirmed line to adjust and how much of it ({@code null} = its whole net quantity; less splits it, D105). */
public record AdjustLine(UUID lineId, BigDecimal quantity) {

    public AdjustLine {
        Objects.requireNonNull(lineId, "lineId");
    }
}
