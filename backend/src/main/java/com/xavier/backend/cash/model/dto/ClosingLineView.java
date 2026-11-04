package com.xavier.backend.cash.model.dto;

import com.xavier.backend.shared.Money;

public record ClosingLineView(String methodCode, Money expected, Money counted, Money difference,
                              String justification) {
}
