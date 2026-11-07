package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.CatalogCodeTakenException;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.CatalogRuleException;
import com.xavier.backend.catalog.model.Item;
import com.xavier.backend.catalog.model.MenuGroup;
import com.xavier.backend.catalog.model.dto.ItemKind;
import com.xavier.backend.catalog.model.dto.ItemLineRequest;
import com.xavier.backend.catalog.model.dto.MenuGroupCreateRequest;
import com.xavier.backend.catalog.model.dto.MenuGroupSummary;
import com.xavier.backend.catalog.model.dto.MenuGroupUpdateRequest;
import com.xavier.backend.catalog.repository.ItemRepository;
import com.xavier.backend.catalog.repository.MenuGroupRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The component groups of a MENU item (D108). Items are company-wide, so their groups need installation-wide
 * {@code CATALOG_MANAGE}. An option must exist and not be a MENU itself (422 {@code MENU_GROUP_INVALID}); release
 * validation checks again, against the items as they are then.
 */
@Service
public class MenuGroupService {

    private final MenuGroupRepository groups;
    private final ItemRepository items;
    private final ItemLines itemLines;
    private final AccessPolicy accessPolicy;
    private final CatalogAudit audit;
    private final Clock clock;

    MenuGroupService(MenuGroupRepository groups, ItemRepository items, ItemLines itemLines, AccessPolicy accessPolicy,
                     CatalogAudit audit, Clock clock) {
        this.groups = groups;
        this.items = items;
        this.itemLines = itemLines;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MenuGroupSummary> list(UUID itemId) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        item(itemId);
        return groups.findAllByItemIdOrderByPositionAscCodeAsc(itemId).stream().map(MenuGroup::toSummary).toList();
    }

    @Transactional
    public MenuGroupSummary create(UUID itemId, MenuGroupCreateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        Item menu = menu(itemId);
        MenuGroup group = save(MenuGroup.create(menu.getId(), details(request.code(), request.name(),
                request.minChoices(), request.maxChoices(), request.position(), request.options()), clock.instant()));
        audit("MENU_GROUP_CREATED", group);
        return group.toSummary();
    }

    @Transactional
    public MenuGroupSummary update(UUID itemId, UUID groupId, MenuGroupUpdateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        MenuGroup group = group(itemId, groupId);
        OptimisticLocking.requireVersion(MenuGroup.class, groupId, group.getVersion(), request.version());
        menu(itemId);
        group.update(details(request.code(), request.name(), request.minChoices(), request.maxChoices(),
                request.position(), request.options()), clock.instant());
        save(group);
        audit("MENU_GROUP_UPDATED", group);
        return group.toSummary();
    }

    @Transactional
    public void delete(UUID itemId, UUID groupId, long version) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        MenuGroup group = group(itemId, groupId);
        OptimisticLocking.requireVersion(MenuGroup.class, groupId, group.getVersion(), version);
        groups.delete(group);
        groups.flush();
        audit("MENU_GROUP_DELETED", group);
    }

    private MenuGroup.Details details(String code, String name, int minChoices, int maxChoices, int position,
                                      List<ItemLineRequest> options) {
        ItemLines.Resolved resolved = itemLines.resolve(options, CatalogRuleException::menuGroupInvalid);
        resolved.anyOfKind(ItemKind.MENU).ifPresent(nested -> {
            throw CatalogRuleException.menuGroupInvalid("Item " + nested.getCode() + " is a MENU: a menu option "
                    + "cannot be a menu.");
        });
        return new MenuGroup.Details(code, name, minChoices, maxChoices, position, resolved.lines());
    }

    private Item menu(UUID itemId) {
        Item item = item(itemId);
        if (item.getKind() != ItemKind.MENU) {
            throw CatalogRuleException.menuGroupInvalid("Item " + item.getCode() + " is " + item.getKind()
                    + ": only a MENU item has menu groups.");
        }
        return item;
    }

    private Item item(UUID id) {
        return items.findById(id).orElseThrow(() -> new CatalogEntryNotFoundException("Item", id));
    }

    private MenuGroup group(UUID itemId, UUID groupId) {
        return groups.findByIdAndItemId(groupId, itemId)
                .orElseThrow(() -> new CatalogEntryNotFoundException("Menu group", groupId));
    }

    private MenuGroup save(MenuGroup group) {
        return UniqueConstraints.saveAndFlush(groups, group,
                () -> new CatalogCodeTakenException("Menu group code '" + group.getCode() + "' in this menu"),
                "menu_group_item_code_uk");
    }

    private void audit(String action, MenuGroup group) {
        audit.record(action, "MenuGroup", group.getId(), null,
                Map.of("itemId", group.getItemId().toString(), "code", group.getCode()));
    }
}
