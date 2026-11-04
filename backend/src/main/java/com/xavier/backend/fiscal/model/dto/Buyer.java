package com.xavier.backend.fiscal.model.dto;

/**
 * The optional buyer fiscal data printed on a document (P17): an inline snapshot, every field optional. Regular
 * customers arrive with the {@code customers} module (R3).
 */
public record Buyer(String name, String taxId, String address) {
}
