package com.xavier.backend.catalog.model.dto;

import java.util.Currency;
import java.util.UUID;

public record PriceListSummary(UUID id, UUID storeId, String name, Currency currency, long version) {
}
