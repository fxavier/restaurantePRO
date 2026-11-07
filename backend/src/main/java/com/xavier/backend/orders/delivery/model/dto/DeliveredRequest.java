package com.xavier.backend.orders.delivery.model.dto;

import jakarta.validation.constraints.NotNull;

public record DeliveredRequest(@NotNull Long version) {
}
