package com.xavier.backend.fiscal.repository;

import com.xavier.backend.fiscal.model.FiscalDocumentPrint;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FiscalDocumentPrintRepository extends JpaRepository<FiscalDocumentPrint, UUID> {

    @Query("select coalesce(max(p.copyNo), 0) from FiscalDocumentPrint p where p.documentId = :documentId")
    int lastCopyNo(@Param("documentId") UUID documentId);
}
