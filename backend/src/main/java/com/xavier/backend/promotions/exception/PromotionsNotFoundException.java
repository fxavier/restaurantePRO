package com.xavier.backend.promotions.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

/** A promotion, a promotion release or a store's published promotions do not exist. 404. */
public class PromotionsNotFoundException extends NotFoundException {

    private PromotionsNotFoundException(String code, String message) {
        super(code, message);
    }

    public static PromotionsNotFoundException promotion(UUID id) {
        return new PromotionsNotFoundException(null, "Promotion " + id + " does not exist.");
    }

    public static PromotionsNotFoundException release(UUID id) {
        return new PromotionsNotFoundException(null, "Promotion release " + id + " does not exist.");
    }

    public static PromotionsNotFoundException noPublished(UUID storeId) {
        return new PromotionsNotFoundException("NO_PUBLISHED_PROMOTIONS",
                "Store " + storeId + " has no published promotions.");
    }
}
