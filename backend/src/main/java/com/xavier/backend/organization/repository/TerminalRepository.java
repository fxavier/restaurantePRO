package com.xavier.backend.organization.repository;

import com.xavier.backend.organization.model.Terminal;
import com.xavier.backend.organization.model.TerminalStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TerminalRepository extends JpaRepository<Terminal, UUID> {

    boolean existsByStoreIdAndCode(UUID storeId, String code);

    Optional<Terminal> findByEnrollmentCodeHash(String enrollmentCodeHash);

    Optional<Terminal> findByDeviceTokenHashAndStatus(String deviceTokenHash, TerminalStatus status);

    Page<Terminal> findByStoreId(UUID storeId, Pageable pageable);
}
