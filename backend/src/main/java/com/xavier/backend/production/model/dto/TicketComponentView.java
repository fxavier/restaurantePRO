package com.xavier.backend.production.model.dto;

import java.math.BigDecimal;

/** A menu component printed under its item on a round ticket (D108). */
public record TicketComponentView(BigDecimal quantity, String name) {
}
