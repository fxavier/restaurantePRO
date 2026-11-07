package com.xavier.backend.promotions.service;

import com.xavier.backend.promotions.model.ReleaseStatus;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot;
import com.xavier.backend.promotions.repository.PromotionReleaseRepository;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** The frozen promotions a store prices from now: its PUBLISHED release, parsed once per call. No authorization. */
@Service
@Transactional(readOnly = true)
public class PublishedPromotions {

    private final PromotionReleaseRepository releases;
    private final JsonMapper json;

    PublishedPromotions(PromotionReleaseRepository releases, JsonMapper json) {
        this.releases = releases;
        this.json = json;
    }

    public Optional<PromotionSnapshot> current(UUID storeId) {
        return releases.findByStoreIdAndStatus(storeId, ReleaseStatus.PUBLISHED)
                .map(release -> json.readValue(release.getSnapshot(), PromotionSnapshot.class));
    }

    public OptionalLong currentVersion(UUID storeId) {
        return releases.findByStoreIdAndStatus(storeId, ReleaseStatus.PUBLISHED)
                .map(release -> OptionalLong.of(release.getVersionNo())).orElseGet(OptionalLong::empty);
    }
}
