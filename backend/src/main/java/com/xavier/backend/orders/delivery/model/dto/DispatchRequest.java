package com.xavier.backend.orders.delivery.model.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record DispatchRequest(@NotNull Long version, @NotNull UUID courierEmployeeId) {
}
