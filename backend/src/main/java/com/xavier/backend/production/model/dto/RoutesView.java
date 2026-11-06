package com.xavier.backend.production.model.dto;

import java.util.List;
import java.util.UUID;

/** A store's routing table, ordered by destination code. */
public record RoutesView(UUID storeId, List<DestinationRouteView> routes) {
}
