package com.xavier.backend.inventory.recipe.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** A recipe, policy or preparation command its state does not allow: 409 with a stable {@code code}. */
public class RecipeConflictException extends ConflictException {

    public RecipeConflictException(String code, String message) {
        super(code, message);
    }

    /** A used recipe version (or consumption policy version) is a fact: a change is a new version (D115). */
    public static RecipeConflictException immutable(UUID versionId, Object status, String action) {
        return new RecipeConflictException("RECIPE_VERSION_IMMUTABLE",
                "Version " + versionId + " is " + status + " and cannot " + action + ": create a new version.");
    }

    public static RecipeConflictException exists(String target) {
        return new RecipeConflictException("RECIPE_EXISTS", "There is already a recipe for " + target + ".");
    }

    /** Two consumption policy versions created or activated at the same time: one wins, the other retries. */
    public static RecipeConflictException policyVersionTaken() {
        return new RecipeConflictException("POLICY_VERSION_TAKEN",
                "Another consumption policy version was created or activated at the same time: retry.");
    }
}
