package com.xavier.backend.promotions;

import com.xavier.backend.shared.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * One billing line of a closing order (D111): its item and family (from the line's release), its effective gross after
 * every discount, and whether a reward was redeemed on it (a rewarded line never earns, P45).
 */
public record EarnLine(UUID itemId, UUID familyId, Money gross, boolean rewarded) {

    public EarnLine {
        Objects.requireNonNull(gross, "gross");
    }
}
