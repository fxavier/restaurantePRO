package com.xavier.backend.fiscal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/** One requested copy of an issued document (spec §8.4). Append-only. */
@Entity
@Immutable
@Table(schema = "fiscal", name = "fiscal_document_print")
public class FiscalDocumentPrint {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID documentId;

    @Column(nullable = false, updatable = false)
    private int copyNo;

    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(nullable = false, updatable = false)
    private UUID requestedByEmployeeId;

    @Column(nullable = false, updatable = false)
    private UUID terminalId;

    protected FiscalDocumentPrint() {
        // JPA
    }

    public static FiscalDocumentPrint copy(UUID documentId, int copyNo, Instant now, UUID employeeId,
                                           UUID terminalId) {
        FiscalDocumentPrint print = new FiscalDocumentPrint();
        print.documentId = documentId;
        print.copyNo = copyNo;
        print.requestedAt = now;
        print.requestedByEmployeeId = employeeId;
        print.terminalId = terminalId;
        return print;
    }

    public int getCopyNo() {
        return copyNo;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }
}
