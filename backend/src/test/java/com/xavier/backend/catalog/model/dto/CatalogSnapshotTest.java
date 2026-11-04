package com.xavier.backend.catalog.model.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot.Item;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot.Price;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot.Rate;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot.TaxCategory;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot.Variant;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D58: the rate of a line is the schedule entry with the greatest {@code effectiveFrom} ≤ the confirmation instant. */
class CatalogSnapshotTest {

    static final Instant CHANGE = Instant.parse("2026-07-01T00:00:00Z");

    /** Deliberately out of order, and with an informational ratePercent that must never be read. */
    static final TaxCategory VAT = new TaxCategory(UUID.randomUUID(), "VAT", "VAT", "99",
            Instant.parse("2000-01-01T00:00:00Z"), List.of(
                    new Rate("16", CHANGE),
                    new Rate("5", Instant.parse("2000-01-01T00:00:00Z")),
                    new Rate("17", Instant.parse("2999-01-01T00:00:00Z")),
                    new Rate("10", Instant.parse("2020-01-01T00:00:00Z"))));

    @Test
    void justBeforeAScheduleChangeTheOldRateApplies() {
        assertThat(VAT.rateAt(CHANGE.minusNanos(1))).contains(new BigDecimal("10"));
    }

    @Test
    void exactlyAtAScheduleChangeTheNewRateApplies() {
        assertThat(VAT.rateAt(CHANGE)).contains(new BigDecimal("16"));
    }

    @Test
    void afterAScheduleChangeTheNewRateAppliesUntilTheNextOne() {
        assertThat(VAT.rateAt(Instant.parse("2027-01-01T00:00:00Z"))).contains(new BigDecimal("16"));
    }

    @Test
    void beforeTheFirstEntryThereIsNoRate() {
        assertThat(VAT.rateAt(Instant.parse("1999-12-31T23:59:59Z"))).isEmpty();
    }

    @Test
    void aCategoryWithoutRatesHasNoRate() {
        TaxCategory exempt = new TaxCategory(UUID.randomUUID(), "EX", "Exempt", null, null, List.of());

        assertThat(exempt.rateAt(CHANGE)).isEmpty();
    }

    static final Currency MZN = Currency.getInstance("MZN");
    static final Variant LARGE = new Variant(UUID.randomUUID(), "L", "Large", new Price("15.00", "MZN"));
    static final Variant SMALL = new Variant(UUID.randomUUID(), "S", "Small", null);
    static final Item COFFEE = new Item(UUID.randomUUID(), "CAFE", "Coffee", UUID.randomUUID(), UUID.randomUUID(),
            VAT.id(), "BAR", false, new Price("12.50", "MZN"), List.of(LARGE, SMALL), List.of());

    @Test
    void aVariantWithItsOwnPriceSellsAtThatPrice() {
        assertThat(COFFEE.priceOf(LARGE.id())).isEqualTo(Money.of("15.00", MZN));
    }

    @Test
    void aVariantWithoutAPriceSellsAtTheItemPrice() {
        assertThat(COFFEE.priceOf(SMALL.id())).isEqualTo(Money.of("12.50", MZN));
    }

    @Test
    void withoutAVariantTheItemSellsAtTheItemPrice() {
        assertThat(COFFEE.priceOf(null)).isEqualTo(Money.of("12.50", MZN));
    }

    @Test
    void aVariantOfAnotherItemBreaksARule() {
        assertThatThrownBy(() -> COFFEE.priceOf(UUID.randomUUID())).isInstanceOf(InvalidCatalogEntryException.class);
    }
}
