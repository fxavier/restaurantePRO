package com.xavier.backend.catalog.model.dto;

import java.util.UUID;

/**
 * One reason the working copy cannot be published, listed in the {@code errors} property of the 422.
 *
 * @param code     {@code STORE_WITHOUT_PRICE_LIST}, {@code ITEM_WITHOUT_PRICE} or {@code ITEM_WITHOUT_TAX_RATE}
 * @param itemId   the offending item, or {@code null} for a store-level problem
 * @param itemCode its code, or {@code null}
 */
public record ReleaseValidationError(String code, UUID itemId, String itemCode, String message) {
}
