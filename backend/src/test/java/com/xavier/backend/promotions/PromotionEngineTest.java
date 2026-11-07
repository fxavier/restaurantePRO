package com.xavier.backend.promotions;

import static com.xavier.backend.promotions.EngineFixtures.AT;
import static com.xavier.backend.promotions.EngineFixtures.FAMILY;
import static com.xavier.backend.promotions.EngineFixtures.ITEM;
import static com.xavier.backend.promotions.EngineFixtures.OTHER;
import static com.xavier.backend.promotions.EngineFixtures.RELEASE;
import static com.xavier.backend.promotions.EngineFixtures.VARIANT;
import static com.xavier.backend.promotions.EngineFixtures.amountOff;
import static com.xavier.backend.promotions.EngineFixtures.families;
import static com.xavier.backend.promotions.EngineFixtures.fixedPrice;
import static com.xavier.backend.promotions.EngineFixtures.items;
import static com.xavier.backend.promotions.EngineFixtures.line;
import static com.xavier.backend.promotions.EngineFixtures.membersOnly;
import static com.xavier.backend.promotions.EngineFixtures.mzn;
import static com.xavier.backend.promotions.EngineFixtures.percentOff;
import static com.xavier.backend.promotions.EngineFixtures.request;
import static com.xavier.backend.promotions.EngineFixtures.snapshot;
import static com.xavier.backend.promotions.EngineFixtures.variants;
import static com.xavier.backend.promotions.EngineFixtures.withModes;
import static com.xavier.backend.promotions.EngineFixtures.withValidity;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.promotions.model.dto.AppliedPromotion;
import com.xavier.backend.promotions.model.dto.PricedLine;
import com.xavier.backend.promotions.model.dto.PricingLine;
import com.xavier.backend.promotions.model.dto.PricingRequest;
import com.xavier.backend.promotions.model.dto.PricingResult;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot.Promotion;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot.Scope;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D101, D103, P43: one promotion per line, an effective unit price between 0 and the list price. */
class PromotionEngineTest {

    static final ZoneId MAPUTO = ZoneId.of("Africa/Maputo");

    @Test
    void percentOffRoundsHalfUpPerUnit() {
        Promotion tenOff = percentOff("TEN", "10", 1, items(ITEM));

        PricedLine priced = only(PromotionEngine.evaluate(snapshot(tenOff), request(line("1.25")), MAPUTO));

        assertThat(priced.unitPrice()).isEqualTo(mzn("1.13"));
        assertThat(priced.listUnitPrice()).isEqualTo(mzn("1.25"));
        assertThat(priced.promotion()).isEqualTo(new AppliedPromotion(tenOff.id(), "TEN", "Promo TEN", "PERCENT_OFF"));
    }

    @Test
    void theResultNamesTheReleaseAndKeepsTheRequestOrder() {
        PricingLine first = line("3.00");
        PricingLine second = line(OTHER, null, null, "4.00");

        PricingResult result = PromotionEngine.evaluate(snapshot(), request(first, second), MAPUTO);

        assertThat(result.releaseId()).isEqualTo(RELEASE);
        assertThat(result.version()).isEqualTo(7L);
        assertThat(result.lines()).extracting(PricedLine::key).containsExactly(first.key(), second.key());
        assertThat(result.lines()).extracting(PricedLine::unitPrice).containsExactly(mzn("3.00"), mzn("4.00"));
        assertThat(result.lines()).extracting(PricedLine::promotion).containsOnlyNulls();
    }

    @Test
    void anAmountOffNeverGoesBelowZero() {
        PricedLine priced = only(PromotionEngine.evaluate(snapshot(amountOff("BIG", "5.00", 1, items(ITEM))),
                request(line("3.00")), MAPUTO));

        assertThat(priced.unitPrice()).isEqualTo(mzn("0.00"));
    }

    @Test
    void aFixedPriceAboveTheListPriceIsIgnoredSoTheNextPromotionApplies() {
        Promotion dearer = fixedPrice("DEAR", "9.00", 5, items(ITEM));
        Promotion small = amountOff("SMALL", "0.50", 1, items(ITEM));

        PricedLine priced = only(PromotionEngine.evaluate(snapshot(dearer, small), request(line("3.00")), MAPUTO));

        assertThat(priced.unitPrice()).isEqualTo(mzn("2.50"));
        assertThat(priced.promotion().code()).isEqualTo("SMALL");
    }

    @Test
    void theHighestPriorityWinsEvenWhenItGivesAHigherPrice() {
        Promotion strong = percentOff("STRONG", "50", 1, items(ITEM));
        Promotion preferred = percentOff("PREFERRED", "10", 2, families(FAMILY));

        PricedLine priced = only(PromotionEngine.evaluate(snapshot(strong, preferred), request(line("10.00")), MAPUTO));

        assertThat(priced.promotion().code()).isEqualTo("PREFERRED");
        assertThat(priced.unitPrice()).isEqualTo(mzn("9.00"));
    }

    @Test
    void atEqualPriorityTheLowestPriceWinsThenTheCode() {
        Promotion tenOff = percentOff("B-TEN", "10", 3, items(ITEM));
        Promotion fixed = fixedPrice("C-FIXED", "8.00", 3, items(ITEM));
        Promotion sameAsFixed = amountOff("A-TWO", "2.00", 3, items(ITEM));

        PricedLine priced = only(PromotionEngine.evaluate(snapshot(tenOff, fixed, sameAsFixed),
                request(line("10.00")), MAPUTO));

        assertThat(priced.unitPrice()).isEqualTo(mzn("8.00"));
        assertThat(priced.promotion().code()).isEqualTo("A-TWO");
    }

    @Test
    void theScopeMatchesTheItemTheVariantOrTheFamily() {
        PromotionSnapshot snapshot = snapshot(amountOff("V", "1.00", 1, variants(VARIANT)));

        List<PricedLine> lines = PromotionEngine.evaluate(snapshot, request(
                line(ITEM, VARIANT, FAMILY, "5.00"), line(ITEM, null, FAMILY, "5.00")), MAPUTO).lines();

        assertThat(lines).extracting(PricedLine::unitPrice).containsExactly(mzn("4.00"), mzn("5.00"));
        assertThat(only(PromotionEngine.evaluate(snapshot(amountOff("F", "1.00", 1, families(FAMILY))),
                request(line(OTHER, null, FAMILY, "5.00")), MAPUTO)).unitPrice()).isEqualTo(mzn("4.00"));
        assertThat(only(PromotionEngine.evaluate(snapshot(amountOff("F", "1.00", 1, families(FAMILY))),
                request(line(OTHER, null, null, "5.00")), MAPUTO)).promotion()).isNull();
    }

    @Test
    void validityStartsInclusiveAndEndsExclusive() {
        PromotionSnapshot snapshot = snapshot(withValidity(amountOff("W", "1.00", 1, items(ITEM)),
                AT, AT.plus(Duration.ofDays(1))));

        assertThat(price(snapshot, request(AT.minusMillis(1), line("5.00")))).isEqualTo(mzn("5.00"));
        assertThat(price(snapshot, request(AT, line("5.00")))).isEqualTo(mzn("4.00"));
        assertThat(price(snapshot, request(AT.plus(Duration.ofDays(1)), line("5.00")))).isEqualTo(mzn("5.00"));
    }

    @Test
    void modesAndMembershipFilterThePromotions() {
        PromotionSnapshot takeawayOnly = snapshot(withModes(amountOff("TK", "1.00", 1, items(ITEM)), "TAKEAWAY"));
        PromotionSnapshot members = snapshot(membersOnly(amountOff("MB", "1.00", 1, items(ITEM))));
        PricingLine line = line("5.00");

        assertThat(price(takeawayOnly, new PricingRequest(EngineFixtures.STORE, AT, "DINE_IN", false, List.of(line))))
                .isEqualTo(mzn("5.00"));
        assertThat(price(takeawayOnly, new PricingRequest(EngineFixtures.STORE, AT, "TAKEAWAY", false, List.of(line))))
                .isEqualTo(mzn("4.00"));
        assertThat(price(members, new PricingRequest(EngineFixtures.STORE, AT, "DINE_IN", false, List.of(line))))
                .isEqualTo(mzn("5.00"));
        assertThat(price(members, new PricingRequest(EngineFixtures.STORE, AT, "DINE_IN", true, List.of(line))))
                .isEqualTo(mzn("4.00"));
    }

    /**
     * Property (seeded): over random promotion sets and lines, every price is within [0, list], a line with no
     * promotion keeps the list price, the result is independent of the order the snapshot lists the promotions in,
     * a priced line carries a promotion that matches its scope, and each line gets exactly what a brute-force oracle
     * picks (highest priority, then lowest resulting price, then lowest code).
     */
    @Test
    void randomPromotionSetsStayWithinBoundsAndDoNotDependOnTheirOrder() {
        Random random = new Random(20261106);
        List<UUID> itemPool = List.of(ITEM, OTHER, UUID.fromString("0190a000-0000-7000-8000-000000000507"));
        for (int scenario = 0; scenario < 2_000; scenario++) {
            List<Promotion> promotions = randomPromotions(random, itemPool);
            List<PricingLine> lines = new ArrayList<>();
            for (int i = 0; i < 1 + random.nextInt(4); i++) {
                lines.add(line(itemPool.get(random.nextInt(itemPool.size())), null, FAMILY, randomAmount(random)));
            }
            PricingRequest request = request(lines.toArray(PricingLine[]::new));
            List<Promotion> shuffled = new ArrayList<>(promotions);
            Collections.shuffle(shuffled, random);

            PricingResult result = PromotionEngine.evaluate(snapshot(promotions.toArray(Promotion[]::new)), request,
                    MAPUTO);
            PricingResult again = PromotionEngine.evaluate(snapshot(shuffled.toArray(Promotion[]::new)), request,
                    MAPUTO);

            assertThat(again).as("scenario %d: order of promotions", scenario).isEqualTo(result);
            for (int i = 0; i < lines.size(); i++) {
                PricedLine priced = result.lines().get(i);
                Oracle expected = oracle(promotions, lines.get(i));
                assertThat(priced.unitPrice()).as("scenario %d line %d: oracle price", scenario, i)
                        .isEqualTo(expected.price());
                assertThat(priced.promotion() == null ? null : priced.promotion().code())
                        .as("scenario %d line %d: oracle promotion", scenario, i).isEqualTo(expected.code());
                Money list = lines.get(i).listUnitPrice();
                assertThat(priced.unitPrice().amount()).as("scenario %d", scenario)
                        .isBetween(BigDecimal.ZERO.setScale(2), list.amount());
                assertThat(priced.unitPrice().currency()).isEqualTo(list.currency());
                if (priced.promotion() == null) {
                    assertThat(priced.unitPrice()).isEqualTo(list);
                } else {
                    UUID item = lines.get(i).itemId();
                    assertThat(promotions).filteredOn(p -> p.id().equals(priced.promotion().promotionId()))
                            .singleElement().satisfies(p -> assertThat(p.scope().itemIds()).contains(item));
                }
            }
        }
    }

    private record Oracle(Money price, String code) {
    }

    /** Every candidate priced independently, then the D103 order by hand: no comparator shared with the engine. */
    private static Oracle oracle(List<Promotion> promotions, PricingLine line) {
        BigDecimal list = line.listUnitPrice().amount();
        Promotion best = null;
        BigDecimal bestPrice = null;
        for (Promotion promotion : promotions) {
            if (!promotion.scope().itemIds().contains(line.itemId())) {
                continue;
            }
            BigDecimal price = switch (promotion.kind()) {
                case "PERCENT_OFF" -> list.multiply(BigDecimal.valueOf(100).subtract(new BigDecimal(promotion.percent())))
                        .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
                case "AMOUNT_OFF_UNIT" -> list.subtract(new BigDecimal(promotion.amount())).max(BigDecimal.ZERO)
                        .setScale(2);
                default -> new BigDecimal(promotion.amount()).setScale(2);
            };
            if (price.compareTo(list) > 0) {
                continue;
            }
            boolean better = best == null
                    || promotion.priority() > best.priority()
                    || (promotion.priority() == best.priority() && price.compareTo(bestPrice) < 0)
                    || (promotion.priority() == best.priority() && price.compareTo(bestPrice) == 0
                    && promotion.code().compareTo(best.code()) < 0);
            if (better) {
                best = promotion;
                bestPrice = price;
            }
        }
        return best == null ? new Oracle(line.listUnitPrice(), null)
                : new Oracle(Money.of(bestPrice, line.listUnitPrice().currency()), best.code());
    }

    private static List<Promotion> randomPromotions(Random random, List<UUID> itemPool) {
        List<Promotion> promotions = new ArrayList<>();
        for (int i = 0; i < random.nextInt(6); i++) {
            String code = "P" + i;
            int priority = random.nextInt(3);
            Scope scope = items(itemPool.get(random.nextInt(itemPool.size())));
            promotions.add(switch (random.nextInt(3)) {
                case 0 -> percentOff(code, String.valueOf(1 + random.nextInt(100)), priority, scope);
                case 1 -> amountOff(code, randomAmount(random), priority, scope);
                default -> fixedPrice(code, randomAmount(random), priority, scope);
            });
        }
        return promotions;
    }

    private static String randomAmount(Random random) {
        return BigDecimal.valueOf(random.nextInt(2_000), 2).toPlainString();
    }

    private static Money price(PromotionSnapshot snapshot, PricingRequest request) {
        return only(PromotionEngine.evaluate(snapshot, request, MAPUTO)).unitPrice();
    }

    private static PricedLine only(PricingResult result) {
        assertThat(result.lines()).hasSize(1);
        return result.lines().getFirst();
    }
}
