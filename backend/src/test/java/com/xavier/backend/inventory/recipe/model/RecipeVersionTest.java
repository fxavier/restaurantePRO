package com.xavier.backend.inventory.recipe.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.inventory.recipe.exception.RecipeConflictException;
import com.xavier.backend.inventory.recipe.exception.RecipeRuleException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D115: DRAFT → ACTIVE → SUPERSEDED; only a DRAFT is edited; a used version is effective over [from, to). */
class RecipeVersionTest {

    static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    static final UUID ACCOUNT = UUID.randomUUID();

    @Test
    void aDraftIsRevisedAndActivated() {
        RecipeVersion version = draft();
        version.revise(new BigDecimal("2"), "un", "two portions", List.of(flour("100")));

        version.activate(NOW, ACCOUNT, NOW);

        assertThat(version.getStatus()).isEqualTo(VersionStatus.ACTIVE);
        assertThat(version.getYieldQuantity()).isEqualByComparingTo("2");
        assertThat(version.getIngredients()).singleElement()
                .satisfies(i -> assertThat(i.getQuantity()).isEqualByComparingTo("100"));
        assertThat(version.isEffectiveAt(NOW)).isTrue();
        assertThat(version.isEffectiveAt(NOW.minusNanos(1000))).isFalse();
    }

    @Test
    void anActiveVersionIsImmutable() {
        RecipeVersion version = draft();
        version.activate(NOW, ACCOUNT, NOW);

        assertThatThrownBy(() -> version.revise(BigDecimal.ONE, "un", null, List.of(flour("1"))))
                .isInstanceOf(RecipeConflictException.class).hasFieldOrPropertyWithValue("code",
                        "RECIPE_VERSION_IMMUTABLE");
        assertThatThrownBy(() -> version.activate(NOW, ACCOUNT, NOW))
                .isInstanceOf(RecipeConflictException.class);
    }

    @Test
    void supersedingClosesThePeriodAtTheNextStart() {
        RecipeVersion version = draft();
        version.activate(NOW, ACCOUNT, NOW);

        version.supersedeAt(NOW.plusSeconds(3600));

        assertThat(version.getStatus()).isEqualTo(VersionStatus.SUPERSEDED);
        assertThat(version.isEffectiveAt(NOW.plusSeconds(3599))).isTrue();
        assertThat(version.isEffectiveAt(NOW.plusSeconds(3600))).isFalse();
        assertThatThrownBy(() -> version.supersedeAt(NOW.plusSeconds(7200)))
                .isInstanceOf(RecipeConflictException.class);
    }

    @Test
    void theNextVersionStartsAfterTheActiveOne() {
        RecipeVersion version = draft();
        version.activate(NOW, ACCOUNT, NOW);

        assertThatThrownBy(() -> version.supersedeAt(NOW))
                .isInstanceOf(RecipeRuleException.class)
                .hasFieldOrPropertyWithValue("code", "RECIPE_EFFECTIVE_FROM_INVALID");
    }

    @Test
    void aDraftIsNeverEffective() {
        assertThat(draft().isEffectiveAt(NOW)).isFalse();
        assertThatThrownBy(() -> draft().supersedeAt(NOW)).isInstanceOf(RecipeConflictException.class);
    }

    private static RecipeVersion draft() {
        return RecipeVersion.draft(UUID.randomUUID(), 1, BigDecimal.ONE, "un", null, List.of(flour("50")), ACCOUNT,
                NOW);
    }

    private static IngredientLine flour(String quantity) {
        return new IngredientLine(UUID.randomUUID(), new BigDecimal(quantity), "g", IngredientKind.INGREDIENT, null);
    }
}
