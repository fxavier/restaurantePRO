package com.xavier.backend.fiscal;

import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.PaymentSnapshot;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * An issued document as an adapter renders it: its snapshots only, never current catalog or profile data.
 * {@code onAccount} marks a document with a credit share (D97 rule 5); {@code payments} is the payment breakdown frozen
 * at issue, printed on the original and on every copy alike ({@code null} for a document issued before D97).
 */
public record IssuedDocument(UUID id, String documentType, String seriesCode, long number, Instant issuedAt,
                             ZoneId storeZone, String companyName, String companyTaxId, String companyCountry,
                             String storeCode, String storeName, Buyer buyer, List<DocumentView.Line> lines,
                             List<DocumentView.TaxSummary> taxes, Money totalGross, Money totalNet, Money totalTax,
                             String adapterData, String qrPayload, boolean onAccount,
                             PaymentSnapshot payments) {
}
