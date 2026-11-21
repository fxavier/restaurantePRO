package com.xavier.backend.orders.delivery.model.dto;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/** An employee the POS may dispatch a delivery with: send {@code id} as the dispatch's {@code courierEmployeeId}. */
@NullMarked
public record CourierView(UUID id, String code, String name) {
}
