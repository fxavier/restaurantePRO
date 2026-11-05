package com.xavier.backend.production.model;

import com.xavier.backend.production.model.dto.TerminalPrinterView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/** Where a terminal's subtotals and documents print (D56). */
@Entity
@Table(schema = "production", name = "terminal_printer")
public class TerminalPrinter {

    @Id
    private UUID terminalId;

    @Column(nullable = false)
    private UUID storeId;

    @Column(nullable = false)
    private UUID printerId;

    protected TerminalPrinter() {
        // JPA
    }

    public TerminalPrinter(UUID terminalId, UUID storeId, UUID printerId) {
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.printerId = Objects.requireNonNull(printerId, "printerId");
    }

    public UUID getPrinterId() {
        return printerId;
    }

    public TerminalPrinterView toView() {
        return new TerminalPrinterView(terminalId, printerId);
    }
}
