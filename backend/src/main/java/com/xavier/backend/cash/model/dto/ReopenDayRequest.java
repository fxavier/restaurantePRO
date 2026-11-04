package com.xavier.backend.cash.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The approver's code and PIN travel in the X-Approver-Code / X-Approver-Pin headers, never here (D40). */
public record ReopenDayRequest(@NotBlank @Size(max = 500) String reason) {
}
