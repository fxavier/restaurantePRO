package com.xavier.backend.promotions.loyalty.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoyaltyProgramVersionTest {

    static final Instant PUBLISHED_AT = Instant.parse("2026-11-23T12:00:00Z");

    /**
     * A wall clock that stepped back (NTP, skew between instances) must not retire a version before its publication:
     * the CHECK {@code retired_at >= published_at} (V12020) would turn the publish into a 500.
     */
    @Test
    void retiringWithAClockBehindThePublicationRetiresAtThePublication() {
        LoyaltyProgramVersion inForce = publishedAt(PUBLISHED_AT);

        Instant retiredAt = inForce.retire(PUBLISHED_AT.minus(Duration.ofMinutes(5)));

        assertThat(inForce.getRetiredAt()).isEqualTo(PUBLISHED_AT);
        assertThat(retiredAt).isEqualTo(PUBLISHED_AT);
    }

    @Test
    void retiringAfterThePublicationRetiresAtTheClock() {
        LoyaltyProgramVersion inForce = publishedAt(PUBLISHED_AT);
        Instant later = PUBLISHED_AT.plus(Duration.ofDays(1));

        assertThat(inForce.retire(later)).isEqualTo(later);
        assertThat(inForce.getRetiredAt()).isEqualTo(later);
    }

    private static LoyaltyProgramVersion publishedAt(Instant instant) {
        LoyaltyProgramVersion version = LoyaltyProgramVersion.draft(1,
                Money.of(new BigDecimal("10.00"), Currency.getInstance("MZN")), 1, List.of(), List.of(), instant);
        version.publish(UUID.randomUUID(), instant);
        return version;
    }
}
