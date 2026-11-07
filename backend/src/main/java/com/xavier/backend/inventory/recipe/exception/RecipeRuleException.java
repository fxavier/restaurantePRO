package com.xavier.backend.inventory.recipe.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A recipe or preparation rule refused the request: 422 with a stable {@code code} (PRD R4 S47). */
public class RecipeRuleException extends BusinessRuleException {

    public RecipeRuleException(String code, String message) {
        super(code, message);
    }

    public RecipeRuleException(String code, String message, Map<String, ?> properties) {
        super(code, message, properties);
    }

    /** The next version must start after the one in force, so their periods never overlap. */
    public static RecipeRuleException effectiveFromInvalid(Instant activeFrom) {
        return new RecipeRuleException("RECIPE_EFFECTIVE_FROM_INVALID",
                "A new version must take effect after " + activeFrom + ", when the current one did.",
                Map.of("notAfter", activeFrom.toString()));
    }

    /** A version never takes effect in the past: rounds already confirmed keep the version they were sold with. */
    public static RecipeRuleException effectiveFromInPast(Instant now) {
        return new RecipeRuleException("RECIPE_EFFECTIVE_FROM_INVALID",
                "A version takes effect now or later, not before " + now + ".", Map.of("notBefore", now.toString()));
    }

    /** A sale recipe names a catalog item, a preparation recipe a stock item: exactly one of them. */
    public static RecipeRuleException targetInvalid() {
        return new RecipeRuleException("RECIPE_TARGET_INVALID",
                "A recipe is for a catalog item (itemId, optionally variantId) or a prepared stock item "
                        + "(outputStockItemId): exactly one of them.");
    }

    /** {@code CatalogApi.items} does not know the item, or the variant is not one of its variants. */
    public static RecipeRuleException itemNotFound(UUID itemId, UUID variantId) {
        return new RecipeRuleException("ITEM_NOT_FOUND", "Catalog item " + itemId
                + (variantId == null ? "" : " with variant " + variantId) + " was not found.");
    }

    public static RecipeRuleException outputNotPrepared(UUID stockItemId) {
        return new RecipeRuleException("RECIPE_OUTPUT_NOT_PREPARED",
                "Stock item " + stockItemId + " is not PREPARED: only a prepared item has a preparation recipe.");
    }

    /** A preparation recipe that needs, directly or through other preparations, its own output (D115). */
    public static RecipeRuleException cycle(UUID outputStockItemId, UUID through) {
        return new RecipeRuleException("RECIPE_CYCLE", "The recipe of stock item " + outputStockItemId
                + " would need itself through stock item " + through + ".", Map.of("stockItemId", through));
    }

    /** A preparation needs a version of the output item's preparation recipe in force now (D121). */
    public static RecipeRuleException noActiveRecipe(UUID outputStockItemId) {
        return new RecipeRuleException("NO_ACTIVE_RECIPE",
                "Stock item " + outputStockItemId + " has no active preparation recipe.");
    }

    /** A consumption policy version has one rule per family and one default rule. */
    public static RecipeRuleException policyRuleDuplicate(UUID familyId) {
        return new RecipeRuleException("POLICY_RULE_DUPLICATE", "The consumption policy has two rules for "
                + (familyId == null ? "the default" : "family " + familyId) + ".");
    }
}
