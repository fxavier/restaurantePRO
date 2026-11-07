package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.exception.CatalogRuleException;
import com.xavier.backend.catalog.model.dto.MenuGroupSummary;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A component group of a MENU item (D108): the order picks between {@code minChoices} and {@code maxChoices} of its
 * options, each an item or variant with a quantity per menu. Its own invariants are checked here; that the options are
 * sellable is checked when a release is validated, because items change after the group was written.
 */
@Entity
@Table(schema = "catalog", name = "menu_group")
public class MenuGroup {

    /** What an editor sets on create and update. */
    public record Details(String code, String name, int minChoices, int maxChoices, int position,
                          List<ItemLine> options) {
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID itemId;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private int minChoices;

    @Column(nullable = false)
    private int maxChoices;

    @Column(nullable = false)
    private int position;

    @ElementCollection
    @CollectionTable(schema = "catalog", name = "menu_option", joinColumns = @JoinColumn(name = "group_id"))
    @OrderColumn(name = "position")
    private List<ItemLine> options = new ArrayList<>();

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected MenuGroup() {
        // JPA
    }

    public static MenuGroup create(UUID menuItemId, Details details, Instant now) {
        MenuGroup group = new MenuGroup();
        group.itemId = CatalogText.required(menuItemId, "itemId");
        group.createdAt = now;
        group.update(details, now);
        return group;
    }

    /**
     * @throws CatalogRuleException (422 {@code MENU_GROUP_INVALID}) for choice bounds that cannot hold (see
     *                              {@link #boundsHold}), no options, a non-positive quantity, or the same item and
     *                              variant twice
     */
    public void update(Details details, Instant now) {
        if (details.position() < 0) {
            throw CatalogRuleException.menuGroupInvalid("Menu group position must be zero or more.");
        }
        List<ItemLine> lines = CatalogText.required(details.options(), "options");
        if (lines.isEmpty()) {
            throw CatalogRuleException.menuGroupInvalid("Menu group " + details.code() + " has no options.");
        }
        if (!boundsHold(details.minChoices(), details.maxChoices(), lines.size())) {
            throw CatalogRuleException.menuGroupInvalid("Menu group " + details.code() + " needs 0 ≤ minChoices ≤ "
                    + "maxChoices and 1 ≤ maxChoices ≤ " + lines.size() + " options, got " + details.minChoices()
                    + ".." + details.maxChoices() + ".");
        }
        for (int i = 0; i < lines.size(); i++) {
            ItemLine option = lines.get(i);
            if (option.getQuantity().signum() <= 0) {
                throw CatalogRuleException.menuGroupInvalid("The quantity of a menu option must be more than zero.");
            }
            if (option.getItemId().equals(itemId)) {
                throw CatalogRuleException.menuGroupInvalid("A menu cannot be an option of itself.");
            }
            for (int j = 0; j < i; j++) {
                if (lines.get(j).sameKey(option)) {
                    throw CatalogRuleException.menuGroupInvalid("Item " + option.getItemId()
                            + " is an option of menu group " + details.code() + " twice.");
                }
            }
        }
        this.code = CatalogText.required(details.code(), "code");
        this.name = CatalogText.required(details.name(), "name");
        this.minChoices = details.minChoices();
        this.maxChoices = details.maxChoices();
        this.position = details.position();
        this.options.clear();
        this.options.addAll(lines);
        this.updatedAt = now;
    }

    /**
     * An option is picked at most once per group, so an order can meet the bounds only when
     * {@code 0 ≤ minChoices ≤ maxChoices} and {@code 1 ≤ maxChoices ≤ options}.
     */
    public static boolean boundsHold(int minChoices, int maxChoices, int options) {
        return minChoices >= 0 && minChoices <= maxChoices && maxChoices >= 1 && maxChoices <= options;
    }

    /** {@link #boundsHold} for this group as stored (rows written before the rule are checked at release). */
    public boolean boundsHold() {
        return boundsHold(minChoices, maxChoices, options.size());
    }

    public MenuGroupSummary toSummary() {
        return new MenuGroupSummary(id, itemId, code, name, minChoices, maxChoices, position,
                options.stream().map(option -> new MenuGroupSummary.Option(option.getItemId(), option.getVariantId(),
                        option.getQuantity())).toList(), version);
    }

    public UUID getId() {
        return id;
    }

    public UUID getItemId() {
        return itemId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getMinChoices() {
        return minChoices;
    }

    public int getMaxChoices() {
        return maxChoices;
    }

    public int getPosition() {
        return position;
    }

    public List<ItemLine> getOptions() {
        return List.copyOf(options);
    }

    public Long getVersion() {
        return version;
    }
}
