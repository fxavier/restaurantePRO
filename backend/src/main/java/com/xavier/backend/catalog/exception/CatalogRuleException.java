package com.xavier.backend.catalog.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A coded catalog business rule refused the request: 422 with the stable {@code code} (PRD R4 conventions). */
public class CatalogRuleException extends BusinessRuleException {

    private final CatalogRule rule;

    private CatalogRuleException(CatalogRule rule, String detail, Map<String, ?> properties) {
        super(rule.name(), detail, properties);
        this.rule = rule;
    }

    public static CatalogRuleException menuGroupInvalid(String detail) {
        return new CatalogRuleException(CatalogRule.MENU_GROUP_INVALID, detail, Map.of());
    }

    /** {@code where}: "a macro" or "an automatic item". */
    public static CatalogRuleException menuNotAllowed(String itemCode, String where) {
        return new CatalogRuleException(CatalogRule.MENU_NOT_ALLOWED, "Item " + itemCode + " is a MENU: " + where
                + " cannot name it, because a menu needs its component choices.", Map.of());
    }

    /** {@code itemIds}: the requested items that the store's published release does not sell. */
    public static CatalogRuleException itemNotPublished(UUID storeId, Collection<UUID> itemIds) {
        return new CatalogRuleException(CatalogRule.ITEM_NOT_PUBLISHED, "Item(s) " + itemIds
                + " are not in the published catalog of store " + storeId + ".",
                Map.of("itemIds", List.copyOf(itemIds)));
    }

    public CatalogRule getRule() {
        return rule;
    }
}
