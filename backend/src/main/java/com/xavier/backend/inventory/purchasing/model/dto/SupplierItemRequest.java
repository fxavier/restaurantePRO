package com.xavier.backend.inventory.purchasing.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A stock item a supplier sells, in a pack of that item or (no pack) in its base unit. {@code unitCost} is the price
 * of one pack (or one base unit), an optional starting last cost; confirmed receipts update it (D123).
 */
public record SupplierItemRequest(
        @NotNull UUID stockItemId,
        UUID packId,
        @Size(max = 60) String supplierCode,
        @PositiveOrZero @Digits(integer = 13, fraction = 6) BigDecimal unitCost) {
}
