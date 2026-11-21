package com.xavier.backend.identity.model.dto;

import com.xavier.backend.access.Permission;
import org.jspecify.annotations.NullMarked;

/**
 * One entry of the role editor's permission catalogue. {@code sensitive} is {@link Permission#sensitive()} (a
 * BackOffice account needs a fresh second factor to use it, D142); {@code area} is the first word of the name (e.g.
 * {@code CASH} for {@code CASH_CLOSING_APPROVE}), for grouping only. A permission's store or company scope is not
 * code-defined: any permission may be granted installation-wide or in one store.
 */
@NullMarked
public record PermissionView(String name, boolean sensitive, String area) {

    public static PermissionView of(Permission permission) {
        String name = permission.name();
        int separator = name.indexOf('_');
        return new PermissionView(name, permission.sensitive(), separator < 0 ? name : name.substring(0, separator));
    }
}
