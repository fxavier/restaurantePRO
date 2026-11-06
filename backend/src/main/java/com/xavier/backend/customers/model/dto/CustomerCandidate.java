package com.xavier.backend.customers.model.dto;

import java.util.List;
import java.util.UUID;

/** One search result: enough for a person to recognize the customer (spec §5: never selected for them). */
public record CustomerCandidate(UUID id, String name, String taxId, List<String> phones, boolean active) {
}
