package com.xavier.backend.promotions.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.promotions.exception.PromotionConflictException;
import com.xavier.backend.promotions.exception.PromotionRuleException;
import com.xavier.backend.promotions.exception.PromotionsNotFoundException;
import com.xavier.backend.promotions.model.PromotionRelease;
import com.xavier.backend.promotions.model.ReleaseStatus;
import com.xavier.backend.promotions.model.dto.PromotionReleaseCreateRequest;
import com.xavier.backend.promotions.model.dto.PromotionReleaseError;
import com.xavier.backend.promotions.model.dto.PromotionReleaseView;
import com.xavier.backend.promotions.model.event.PromotionsPublished;
import com.xavier.backend.promotions.repository.PromotionReleaseRepository;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import com.xavier.backend.shared.persistence.StoreProbe;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Promotion releases of a store (D102), the same machine as catalog releases (D31). Every step needs
 * {@code PROMOTION_MANAGE} in the store; for a release addressed by id its store is read first (D42 (b)).
 */
@Service
public class PromotionReleaseService {

    private final PromotionReleaseRepository releases;
    private final PromotionWorkingCopy workingCopy;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final StoreProbe probe;
    private final PromotionsAudit audit;
    private final ApplicationEventPublisher events;
    private final ChangeNotifier notifier;
    private final JsonMapper json;
    private final Clock clock;

    PromotionReleaseService(PromotionReleaseRepository releases, PromotionWorkingCopy workingCopy,
                            OrganizationApi organization, AccessPolicy accessPolicy, StoreProbe probe,
                            PromotionsAudit audit, ApplicationEventPublisher events, ChangeNotifier notifier,
                            JsonMapper json, Clock clock) {
        this.releases = releases;
        this.workingCopy = workingCopy;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.probe = probe;
        this.audit = audit;
        this.events = events;
        this.notifier = notifier;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public PromotionReleaseView create(PromotionReleaseCreateRequest request) {
        accessPolicy.require(Permission.PROMOTION_MANAGE, request.storeId());
        if (!organization.storeExists(request.storeId())) {
            throw PromotionRuleException.invalid("Store " + request.storeId() + " does not exist.");
        }
        PromotionRelease release = releases.saveAndFlush(
                PromotionRelease.draft(request.storeId(), accessPolicy.current().userId(), clock.instant()));
        audit.record("PROMOTION_RELEASE_CREATED", "PromotionRelease", release.getId(), release.getStoreId(), Map.of());
        return view(release);
    }

    /** Checks the working copy now; on success the release is VALIDATED, otherwise a 422 lists every problem. */
    @Transactional
    public PromotionReleaseView validate(UUID releaseId) {
        PromotionRelease release = authorized(releaseId);
        release.requireValidatable();
        PromotionWorkingCopy.View view = requireNoErrors(workingCopy.of(release.getStoreId()));
        release.markValidated(clock.instant(), json.writeValueAsString(view.warnings()));
        releases.saveAndFlush(release);
        audit.record("PROMOTION_RELEASE_VALIDATED", "PromotionRelease", releaseId, release.getStoreId(), Map.of());
        return view(release);
    }

    /**
     * Freezes the store's working copy into this VALIDATED release, validated again because it may have changed, and
     * makes it the one the store prices from; the previous publication is retired. A concurrent publication for the
     * same store loses with 409. After the commit the store's POS clients get a {@code CATALOG} change notice.
     */
    @Transactional
    public PromotionReleaseView publish(UUID releaseId) {
        PromotionRelease release = authorized(releaseId);
        UUID storeId = release.getStoreId();
        release.requirePublishable();
        PromotionWorkingCopy.View view = requireNoErrors(workingCopy.of(storeId));
        Instant now = clock.instant();

        Optional<PromotionRelease> previous = releases.findByStoreIdAndStatus(storeId, ReleaseStatus.PUBLISHED);
        previous.ifPresent(published -> {
            published.retire(now);
            releases.saveAndFlush(published); // before ours, so the one-published-per-store index never sees two
        });
        long version = releases.maxVersionNo(storeId) + 1;
        release.publish(version, json.writeValueAsString(view.snapshot(releaseId, version)),
                json.writeValueAsString(view.warnings()), accessPolicy.current().userId(), now);
        UniqueConstraints.saveAndFlush(releases, release,
                () -> PromotionConflictException.releaseState(releaseId,
                        "another release of store " + storeId + " was published at the same time"),
                "promotion_release_one_published_per_store_ux", "promotion_release_store_version_uk");

        Map<String, Object> details = new HashMap<>();
        details.put("version", version);
        details.put("promotions", view.promotions().size());
        previous.ifPresent(retired -> details.put("retiredReleaseId", retired.getId().toString()));
        audit.record("PROMOTION_RELEASE_PUBLISHED", "PromotionRelease", releaseId, storeId, details);
        events.publishEvent(new PromotionsPublished(storeId, releaseId, version));
        notifier.notifyAfterCommit(storeId, ChangeTopic.CATALOG, null); // store-wide: the POS re-reads its prices
        return view(release);
    }

    @Transactional(readOnly = true)
    public PromotionReleaseView get(UUID releaseId) {
        return view(authorized(releaseId));
    }

    @Transactional(readOnly = true)
    public PageResponse<PromotionReleaseView> listForStore(UUID storeId, Pageable pageable) {
        accessPolicy.require(Permission.PROMOTION_MANAGE, storeId);
        return PageResponse.from(releases.findAllByStoreId(storeId, pageable).map(this::view));
    }

    /**
     * D42 (b): the release's store alone (404), then the permission there (403), then the full row. The store is probed
     * through {@code shared.store_of}: row-level security hides another store's release from a plain read.
     */
    private PromotionRelease authorized(UUID releaseId) {
        UUID storeId = probe.storeOf("promotions.promotion_release", releaseId)
                .orElseThrow(() -> PromotionsNotFoundException.release(releaseId));
        accessPolicy.require(Permission.PROMOTION_MANAGE, storeId);
        return releases.findById(releaseId).orElseThrow(() -> PromotionsNotFoundException.release(releaseId));
    }

    private PromotionReleaseView view(PromotionRelease release) {
        String warnings = release.getValidationWarnings();
        return release.toView(warnings == null ? null
                : List.of(json.readValue(warnings, PromotionReleaseError[].class)));
    }

    private static PromotionWorkingCopy.View requireNoErrors(PromotionWorkingCopy.View view) {
        if (!view.errors().isEmpty()) {
            throw PromotionRuleException.releaseInvalid(view.errors());
        }
        return view;
    }
}
