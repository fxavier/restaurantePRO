package com.xavier.backend.organization.repository;

import com.xavier.backend.organization.model.Terminal;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TerminalRepository extends JpaRepository<Terminal, UUID> {

    /** Matches {@code upper(code)}, as the unique index does: a row written before FE-F21 may be lowercase. */
    boolean existsByStoreIdAndCodeIgnoreCase(UUID storeId, String code);

    Page<Terminal> findByStoreId(UUID storeId, Pageable pageable);

    /**
     * The device, locked for its enrolment: a code's presentations and its completion run one at a time, so a wrong
     * attempt's failure count never makes the right device's completion fail on a version conflict.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Terminal t where t.id = :id")
    Optional<Terminal> lockById(@Param("id") UUID id);
}
