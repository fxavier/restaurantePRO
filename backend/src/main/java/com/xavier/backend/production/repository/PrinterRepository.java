package com.xavier.backend.production.repository;

import com.xavier.backend.production.model.Printer;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrinterRepository extends JpaRepository<Printer, UUID> {

    @Query("select p.storeId from Printer p where p.id = :id")
    Optional<UUID> findStoreIdById(@Param("id") UUID id);

    Page<Printer> findByStoreId(UUID storeId, Pageable pageable);

    /** Some printer redirects to {@code printerId} (the CHECK makes redirect_printer_id imply REDIRECT). */
    boolean existsByRedirectPrinterId(UUID printerId);

    /** Row locks in id order, so two mode changes never wait on each other in a cycle. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Printer p where p.id in :ids order by p.id")
    List<Printer> lockAll(@Param("ids") Collection<UUID> ids);
}
