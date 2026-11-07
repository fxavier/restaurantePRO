package com.xavier.backend.orders.model.event;

import java.math.BigDecimal;
import org.springframework.modulith.NamedInterface;

/**
 * A chosen component of a menu line as production prints it on its own destination (D108): {@code quantity} is for
 * the whole line (per menu unit × menus), {@code destination} {@code null} when nothing prepares it.
 */
@NamedInterface("event")
public record ComponentLine(String itemCode, String description, BigDecimal quantity, String destination) {
}
