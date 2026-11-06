package com.xavier.backend.promotions.model.dto;

import java.util.UUID;

/** The promotion a priced line got, as the line snapshots it: id, code, name and kind (the promotion type name). */
public record AppliedPromotion(UUID promotionId, String code, String name, String kind) {
}
