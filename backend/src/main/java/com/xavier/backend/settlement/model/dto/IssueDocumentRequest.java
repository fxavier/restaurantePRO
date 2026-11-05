package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.fiscal.model.dto.Buyer;

/** {@code POST /api/v1/pos/orders/{id}/documents}: the optional buyer printed on the document (P17). */
public record IssueDocumentRequest(Buyer buyer) {
}
