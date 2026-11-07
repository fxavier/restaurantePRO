package com.xavier.backend.promotions.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.promotions.exception.PromotionsNotFoundException;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The POS reads its store's published promotions for previews ({@code CATALOG_VIEW} in the session's store, D39). */
@Service
public class PosPromotionsService {

    private final PublishedPromotions published;
    private final AccessPolicy accessPolicy;

    PosPromotionsService(PublishedPromotions published, AccessPolicy accessPolicy) {
        this.published = published;
        this.accessPolicy = accessPolicy;
    }

    /** @throws PromotionsNotFoundException (404 {@code NO_PUBLISHED_PROMOTIONS}) when the store never published */
    @Transactional(readOnly = true)
    public PromotionSnapshot current() {
        UUID storeId = accessPolicy.requirePos(Permission.CATALOG_VIEW).storeId();
        return published.current(storeId).orElseThrow(() -> PromotionsNotFoundException.noPublished(storeId));
    }
}
