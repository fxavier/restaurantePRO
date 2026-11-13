package com.xavier.backend.identity.oidc.repository;

import com.xavier.backend.identity.oidc.model.ExternalIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, UUID> {

    Optional<ExternalIdentity> findByIssuerAndSubject(String issuer, String subject);

    Optional<ExternalIdentity> findByAccountId(UUID accountId);

    boolean existsByAccountId(UUID accountId);

    long deleteByAccountId(UUID accountId);
}
