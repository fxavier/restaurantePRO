package com.xavier.backend.promotions.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A working-copy promotion as the BackOffice edits it; {@code version} is sent back on every change (D17). */
public record PromotionView(UUID id, String code, String name, String kind, BigDecimal percent, BigDecimal amount,
                            String currency, int priority, Instant validFrom, Instant validTo, boolean membersOnly,
                            List<String> modes, boolean active, PromotionSnapshot.Scope scope,
                            List<PromotionSnapshot.Window> windows, List<UUID> storeIds, long version) {
}
