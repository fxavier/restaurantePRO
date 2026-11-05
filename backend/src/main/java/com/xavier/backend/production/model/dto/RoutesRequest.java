package com.xavier.backend.production.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** The whole routing table of a store: it replaces the current one. An empty list removes every route. */
public record RoutesRequest(@NotNull UUID storeId, @NotNull List<@Valid @NotNull DestinationRouteView> routes) {
}
