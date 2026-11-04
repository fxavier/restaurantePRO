package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.IllegalReleaseStateException;
import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.exception.ReleaseValidationFailedException;
import com.xavier.backend.catalog.model.ConfigurationRelease;
import com.xavier.backend.catalog.model.ReleaseStatus;
import com.xavier.backend.catalog.model.dto.PublishRequest;
import com.xavier.backend.catalog.model.dto.ReleaseCreateRequest;
import com.xavier.backend.catalog.model.dto.ReleaseSummary;
import com.xavier.backend.catalog.model.dto.ReleaseValidationError;
import com.xavier.backend.catalog.model.event.CatalogPublished;
import com.xavier.backend.catalog.repository.ConfigurationReleaseRepository;
import com.xavier.backend.organization.OrganizationApi;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Catalog releases of a store (D31, spec §8.3). Creating and validating need {@code CATALOG_MANAGE} in the store;
 * publishing needs {@code CATALOG_PUBLISH} there and runs inside the Idempotency-Key transaction.
 */
@Service
public class ReleaseService {

    private final ConfigurationReleaseRepository releases;
    private final WorkingCopy workingCopy;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final CatalogAudit audit;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Clock clock;

    ReleaseService(ConfigurationReleaseRepository releases, WorkingCopy workingCopy, OrganizationApi organization,
                   AccessPolicy accessPolicy, CatalogAudit audit, ApplicationEventPublisher events, JsonMapper json,
                   Clock clock) {
        this.releases = releases;
        this.workingCopy = workingCopy;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public ReleaseSummary create(ReleaseCreateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE, request.storeId());
        if (!organization.storeExists(request.storeId())) {
            throw new InvalidCatalogEntryException("Store " + request.storeId() + " does not exist");
        }
        ConfigurationRelease release = releases.saveAndFlush(
                ConfigurationRelease.draft(request.storeId(), accessPolicy.current().userId(), clock.instant()));
        audit.record("CATALOG_RELEASE_CREATED", "ConfigurationRelease", release.getId(), release.getStoreId(), Map.of());
        return release.toSummary();
    }

    /** Checks the working copy now; on success the release is VALIDATED, otherwise a 422 lists every problem. */
    @Transactional
    public ReleaseSummary validate(UUID releaseId) {
        ConfigurationRelease release = release(releaseId);
        accessPolicy.require(Permission.CATALOG_MANAGE, release.getStoreId());
        release.requireValidatable();
        Instant now = clock.instant();
        requireNoErrors(workingCopy.of(release.getStoreId(), now).errors());
        release.markValidated(now);
        releases.saveAndFlush(release);
        audit.record("CATALOG_RELEASE_VALIDATED", "ConfigurationRelease", releaseId, release.getStoreId(), Map.of());
        return release.toSummary();
    }

    /**
     * Freezes the store's working copy into this VALIDATED release and makes it the one the POS sells from; the
     * previous publication is retired. The working copy is validated again, because it may have changed since
     * the release was validated. A concurrent publication for the same store loses with 409 (partial unique
     * index, version uniqueness, or {@code @Version} on the retired release).
     */
    @Transactional
    public ReleaseSummary publish(UUID releaseId, PublishRequest request) {
        ConfigurationRelease release = release(releaseId);
        UUID storeId = release.getStoreId();
        accessPolicy.require(Permission.CATALOG_PUBLISH, storeId);
        release.requirePublishable();
        Instant now = clock.instant();
        WorkingCopy.View view = workingCopy.of(storeId, now);
        requireNoErrors(view.errors());

        Optional<ConfigurationRelease> previous = releases.findByStoreIdAndStatus(storeId, ReleaseStatus.PUBLISHED);
        previous.ifPresent(published -> {
            published.retire(now);
            releases.saveAndFlush(published); // before ours, so the one-published-per-store index never sees two
        });
        long version = releases.maxCatalogVersion(storeId) + 1;
        UUID author = accessPolicy.current().userId();
        release.publish(version, json.writeValueAsString(view.snapshot(releaseId, version)), author, now);
        UniqueConstraint.saveAndFlush(releases, release,
                () -> new IllegalReleaseStateException("Another release of store " + storeId + " was published at the same time"),
                "configuration_release_one_published_per_store_ux", "configuration_release_store_version_uk");

        auditPublication(release, previous, request);
        events.publishEvent(new CatalogPublished(storeId, releaseId, version, now));
        return release.toSummary();
    }

    @Transactional(readOnly = true)
    public ReleaseSummary get(UUID releaseId) {
        ConfigurationRelease release = release(releaseId);
        accessPolicy.require(Permission.CATALOG_MANAGE, release.getStoreId());
        return release.toSummary();
    }

    @Transactional(readOnly = true)
    public List<ReleaseSummary> listForStore(UUID storeId) {
        accessPolicy.require(Permission.CATALOG_MANAGE, storeId);
        return releases.findAllByStoreIdOrderByCreatedAtDesc(storeId).stream().map(ConfigurationRelease::toSummary).toList();
    }

    private static void requireNoErrors(List<ReleaseValidationError> errors) {
        if (!errors.isEmpty()) {
            throw new ReleaseValidationFailedException(errors);
        }
    }

    private void auditPublication(ConfigurationRelease release, Optional<ConfigurationRelease> previous,
                                  PublishRequest request) {
        Map<String, Object> details = new HashMap<>();
        details.put("version", release.getCatalogVersion());
        details.put("effectiveAt", release.getEffectiveAt().toString());
        previous.ifPresent(retired -> details.put("retiredReleaseId", retired.getId().toString()));
        if (request.note() != null) {
            details.put("note", request.note());
        }
        audit.record("CATALOG_RELEASE_PUBLISHED", "ConfigurationRelease", release.getId(), release.getStoreId(), details);
    }

    private ConfigurationRelease release(UUID id) {
        return releases.findById(id).orElseThrow(() -> new CatalogEntryNotFoundException("Release", id));
    }
}
