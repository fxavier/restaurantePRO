package com.xavier.backend.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Security rule 7 (A82): the item → family map of a published release is cached per {@code (tenantId, releaseId)}.
 * Two tenants that hold the same release id (a forged or colliding one) never read each other's cached families, and
 * a tenant that has no snapshot for the id is not served another tenant's.
 */
class TenantCacheIsolationTest {

    static final UUID RELEASE = UUID.fromString("0190b900-0000-7000-8000-0000000000e1");
    static final UUID STORE = UUID.fromString("0190b900-0000-7000-8000-0000000000e2");
    static final UUID ITEM = UUID.fromString("0190b900-0000-7000-8000-0000000000e3");
    static final UUID FAMILY = UUID.fromString("0190b900-0000-7000-8000-0000000000e4");

    final CatalogApi catalog = mock(CatalogApi.class);
    final TenantScopes tenants = new TenantScopes((com.xavier.backend.access.StoreScopes) null, Optional::empty);
    final ReleaseFamilies families = new ReleaseFamilies(catalog);

    @Test
    void theSameReleaseIdInTwoTenantsKeepsEachTenantsFamilies() {
        // the snapshot a tenant reads is its own: family code = the tenant's name
        when(catalog.snapshot(STORE, RELEASE)).thenAnswer(call -> snapshotWithFamily(currentTenantCode()));

        String inA = TenantTestSupport.inTenant(tenants, TenantTestSupport.A,
                () -> families.familyOf(STORE, RELEASE, ITEM));
        String inB = TenantTestSupport.inTenant(tenants, TenantTestSupport.B,
                () -> families.familyOf(STORE, RELEASE, ITEM));

        assertThat(inA).isEqualTo("tenant-a");
        assertThat(inB).isEqualTo("tenant-b");
    }

    @Test
    void aTenantsOwnReleaseIsParsedOnceAndReused() {
        when(catalog.snapshot(STORE, RELEASE)).thenAnswer(call -> snapshotWithFamily(currentTenantCode()));

        TenantTestSupport.inTenant(tenants, TenantTestSupport.A, () -> families.familyOf(STORE, RELEASE, ITEM));
        TenantTestSupport.inTenant(tenants, TenantTestSupport.A, () -> families.familyOf(STORE, RELEASE, ITEM));

        verify(catalog, times(1)).snapshot(STORE, RELEASE);
    }

    private String currentTenantCode() {
        UUID tenant = tenants.requireTenant();
        return tenant.equals(TenantTestSupport.A) ? "tenant-a" : "tenant-b";
    }

    private static CatalogSnapshot snapshotWithFamily(String familyCode) {
        return new CatalogSnapshot(CatalogSnapshot.SCHEMA_VERSION, STORE, RELEASE, 1, Instant.parse("2026-01-01T00:00:00Z"),
                "MZN", List.of(new CatalogSnapshot.Family(FAMILY, familyCode, "Family", null)), List.of(), List.of(),
                List.of(new CatalogSnapshot.Item(ITEM, "I", "Item", FAMILY, UUID.randomUUID(),
                        UUID.randomUUID(), null, false, null, List.of(), List.of(), null, List.of())));
    }
}
