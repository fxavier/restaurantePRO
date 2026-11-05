package com.xavier.backend.fiscal;

import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * An issued document as an adapter renders it: its snapshots only, never current catalog or profile data.
 * {@code onAccount} marks a credit sale, printed as such (A20).
 */
public record IssuedDocument(UUID id, String documentType, String seriesCode, long number, Instant issuedAt,
                             ZoneId storeZone, String companyName, String companyTaxId, String companyCountry,
                             String storeCode, String storeName, Buyer buyer, List<DocumentView.Line> lines,
                             List<DocumentView.TaxSummary> taxes, Money totalGross, Money totalNet, Money totalTax,
                             String adapterData, String qrPayload, boolean onAccount) {
}
