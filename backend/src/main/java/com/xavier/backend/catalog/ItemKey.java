package com.xavier.backend.catalog;

import java.util.UUID;

/**
 * An item, or one of its variants, as the sold-out flag names it (D109). {@code variantId} {@code null} flags the item
 * itself, and with it every variant: a line of variant V of item I is sold out when {@code (I, null)} or
 * {@code (I, V)} is flagged.
 */
public record ItemKey(UUID itemId, UUID variantId) {
}
