package com.xavier.backend.catalog.model.dto;

import java.util.UUID;

/**
 * Something the store's exclusions leave out of its release (D147). Warnings never block publication.
 *
 * @param code     {@code MENU_OPTION_EXCLUDED}, {@code MACRO_OMITTED}, {@code QUICK_PAGE_ENTRY_OMITTED} or
 *                 {@code AUTOMATIC_ITEM_OMITTED}
 * @param itemId   the excluded item that causes it
 * @param itemCode its code
 */
public record ReleaseValidationWarning(String code, UUID itemId, String itemCode, String message) {
}
