package com.xavier.backend.organization.repository;

import com.xavier.backend.organization.model.CompanyProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** One profile per tenant (D174); callers pass the bound tenant, and row-level security shows no other. */
public interface CompanyProfileRepository extends JpaRepository<CompanyProfile, UUID> {

    Optional<CompanyProfile> findByTenantId(UUID tenantId);

    boolean existsByTenantId(UUID tenantId);
}
