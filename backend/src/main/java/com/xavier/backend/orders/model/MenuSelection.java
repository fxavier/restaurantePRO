package com.xavier.backend.orders.model;

import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.catalog.model.dto.ItemKind;
import com.xavier.backend.orders.exception.OrderRuleException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Checks a menu's choices against its groups in a frozen catalog (D108). Pure. A MENU needs, per group, between
 * {@code minChoices} and {@code maxChoices} of that group's options, each at most once; any other item takes no
 * choices. The result is what confirmation freezes per component: code, description, quantity per menu unit and
 * production destination, from the same release.
 */
public final class MenuSelection {

    private MenuSelection() {
    }

    /** One choice of a draft menu: an option of group {@code groupCode} ({@code variantId} null = the item). */
    public record Choice(String groupCode, UUID itemId, UUID variantId) {

        public Choice {
            Objects.requireNonNull(groupCode, "groupCode");
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    /** A checked choice with what the release says of it. */
    public record Frozen(Choice choice, String itemCode, String description, BigDecimal quantity,
                         String destination) {
    }

    /** @throws OrderRuleException {@code MENU_SELECTION_INVALID} */
    public static List<Frozen> validate(CatalogSnapshot catalog, CatalogSnapshot.Item menu, List<Choice> choices) {
        if (menu.kind() != ItemKind.MENU) {
            if (!choices.isEmpty()) {
                throw OrderRuleException.menuSelectionInvalid(menu.code(), "it is not a menu, it takes no choices");
            }
            return List.of();
        }
        Set<Choice> seen = new HashSet<>();
        List<Frozen> frozen = new ArrayList<>();
        for (Choice choice : choices) {
            CatalogSnapshot.MenuGroup group = menu.menuGroups().stream()
                    .filter(candidate -> candidate.code().equals(choice.groupCode())).findFirst()
                    .orElseThrow(() -> OrderRuleException.menuSelectionInvalid(menu.code(),
                            "it has no group " + choice.groupCode()));
            CatalogSnapshot.MenuOption option = group.options().stream()
                    .filter(candidate -> candidate.itemId().equals(choice.itemId())
                            && Objects.equals(candidate.variantId(), choice.variantId())).findFirst()
                    .orElseThrow(() -> OrderRuleException.menuSelectionInvalid(menu.code(),
                            "item " + choice.itemId() + " is not an option of group " + group.code()));
            if (!seen.add(choice)) {
                throw OrderRuleException.menuSelectionInvalid(menu.code(),
                        "item " + choice.itemId() + " is chosen twice in group " + group.code());
            }
            frozen.add(freeze(catalog, menu, choice, option));
        }
        for (CatalogSnapshot.MenuGroup group : menu.menuGroups()) {
            long chosen = choices.stream().filter(choice -> choice.groupCode().equals(group.code())).count();
            if (chosen < group.minChoices() || chosen > group.maxChoices()) {
                throw OrderRuleException.menuSelectionInvalid(menu.code(), "group " + group.code() + " takes "
                        + group.minChoices() + " to " + group.maxChoices() + " choices, not " + chosen);
            }
        }
        return frozen;
    }

    private static Frozen freeze(CatalogSnapshot catalog, CatalogSnapshot.Item menu, Choice choice,
                                 CatalogSnapshot.MenuOption option) {
        CatalogSnapshot.Item item = catalog.items().stream().filter(candidate -> candidate.id().equals(option.itemId()))
                .findFirst().orElseThrow(() -> OrderRuleException.menuSelectionInvalid(menu.code(),
                        "option " + option.itemId() + " is not in the catalog"));
        String description = item.name();
        if (option.variantId() != null) {
            description = description + " (" + item.variants().stream()
                    .filter(variant -> variant.id().equals(option.variantId())).findFirst()
                    .orElseThrow(() -> OrderRuleException.menuSelectionInvalid(menu.code(),
                            "option variant " + option.variantId() + " is not in the catalog")).name() + ")";
        }
        if (description.length() > 120) {
            description = description.substring(0, 119) + "…";
        }
        return new Frozen(choice, item.code(), description, option.quantity(), item.productionDestination());
    }
}
