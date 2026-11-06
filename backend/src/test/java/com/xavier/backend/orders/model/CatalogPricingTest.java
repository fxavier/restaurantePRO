package com.xavier.backend.orders.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot.Price;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D58: the line snapshot at confirmation — variant price or item price, and the rate in effect at that instant. */
class CatalogPricingTest {

    static final Instant T2020 = Instant.parse("2020-01-01T00:00:00Z");
    static final Instant T2025 = Instant.parse("2025-01-01T00:00:00Z");

    final UUID itemId = UUID.randomUUID();
    final UUID pricedVariant = UUID.randomUUID();
    final UUID unpricedVariant = UUID.randomUUID();
    final UUID taxId = UUID.randomUUID();
    final UUID releaseId = UUID.randomUUID();

    CatalogSnapshot catalog() {
        CatalogSnapshot.TaxCategory vat = new CatalogSnapshot.TaxCategory(taxId, "IVA", "VAT", "17", T2020,
                List.of(new CatalogSnapshot.Rate("16", T2020), new CatalogSnapshot.Rate("17", T2025)));
        CatalogSnapshot.Item burger = new CatalogSnapshot.Item(itemId, "BURGER", "Burger", null, null, taxId, "KITCHEN",
                false, new Price("12.50", "MZN"),
                List.of(new CatalogSnapshot.Variant(pricedVariant, "XL", "Large", new Price("15.00", "MZN")),
                        new CatalogSnapshot.Variant(unpricedVariant, "SM", "Small", null)),
                List.of());
        return new CatalogSnapshot(1, UUID.randomUUID(), releaseId, 7, T2020, "MZN", List.of(), List.of(),
                List.of(vat), List.of(burger));
    }

    @Test
    void theItemItselfSnapshotsItsPriceNameDestinationAndTheRateInEffectThen() {
        LineSnapshot snapshot = CatalogPricing.snapshotOf(catalog(), itemId, null, Instant.parse("2024-06-01T00:00:00Z"));

        assertThat(snapshot).isEqualTo(new LineSnapshot("BURGER", "Burger", null, mzn("12.50"), taxId, "IVA",
                new BigDecimal("16"), releaseId, 7, "KITCHEN"));
    }

    @Test
    void theRateIsTheLatestThatStartedByTheConfirmationInstantNotThePublishedOne() {
        assertThat(CatalogPricing.snapshotOf(catalog(), itemId, null, T2025).taxRatePercent())
                .isEqualByComparingTo("17");
    }

    @Test
    void aVariantSellsAtItsOwnPriceOrElseAtTheItemPrice() {
        assertThat(CatalogPricing.snapshotOf(catalog(), itemId, pricedVariant, T2025))
                .satisfies(s -> {
                    assertThat(s.unitPrice()).isEqualTo(mzn("15.00"));
                    assertThat(s.variantName()).isEqualTo("Large");
                });
        assertThat(CatalogPricing.snapshotOf(catalog(), itemId, unpricedVariant, T2025).unitPrice())
                .isEqualTo(mzn("12.50"));
    }

    @Test
    void anItemOrVariantMissingFromTheCatalogIsItemNotInCatalog() {
        assertRule(() -> CatalogPricing.snapshotOf(catalog(), UUID.randomUUID(), null, T2025), "ITEM_NOT_IN_CATALOG");
        assertRule(() -> CatalogPricing.snapshotOf(catalog(), itemId, UUID.randomUUID(), T2025), "ITEM_NOT_IN_CATALOG");
    }

    @Test
    void noRateStartedYetIsNoTaxRateInEffect() {
        assertRule(() -> CatalogPricing.snapshotOf(catalog(), itemId, null, Instant.parse("2019-12-31T23:59:59Z")),
                "NO_TAX_RATE_IN_EFFECT");
    }

    @Test
    void thePreviewOfADraftIsItsCurrentPriceOrNothingWhenTheItemLeftTheCatalog() {
        assertThat(CatalogPricing.previewPrice(catalog(), itemId, pricedVariant)).contains(mzn("15.00"));
        assertThat(CatalogPricing.previewPrice(catalog(), UUID.randomUUID(), null)).isEmpty();
        assertThat(CatalogPricing.previewPrice(catalog(), itemId, UUID.randomUUID())).isEmpty();
    }

    static void assertRule(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OrderRuleException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    static Money mzn(String amount) {
        return Money.of(amount, Currency.getInstance("MZN"));
    }
}
