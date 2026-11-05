package com.xavier.backend.production.repository;

import com.xavier.backend.production.model.TerminalPrinter;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TerminalPrinterRepository extends JpaRepository<TerminalPrinter, UUID> {

    List<TerminalPrinter> findByStoreIdOrderByTerminalId(UUID storeId);

    /** Runs at once (before the inserts of the replacement assignments). */
    @Modifying(flushAutomatically = true)
    @Query("delete from TerminalPrinter t where t.storeId = :storeId")
    int deleteByStore(@Param("storeId") UUID storeId);
}
