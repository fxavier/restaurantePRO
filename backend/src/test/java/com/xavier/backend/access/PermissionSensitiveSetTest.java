package com.xavier.backend.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** D142 / P54: exactly these 15 permissions need a fresh second factor; every other one does not. */
class PermissionSensitiveSetTest {

    static final Set<Permission> D142 = EnumSet.of(
            Permission.COMPANY_MANAGE,
            Permission.USER_MANAGE,
            Permission.ROLE_MANAGE,
            Permission.EMPLOYEE_MANAGE,
            Permission.TERMINAL_MANAGE,
            Permission.SUPPORT_ACCESS_MANAGE,
            Permission.FISCAL_MANAGE,
            Permission.CATALOG_PUBLISH,
            Permission.PROMOTION_MANAGE,
            Permission.CASH_CLOSING_APPROVE,
            Permission.CASH_ADJUST,
            Permission.CREDIT_LIMIT_MANAGE,
            Permission.PAYMENT_METHOD_MANAGE,
            Permission.REPORT_EXPORT,
            Permission.AUDIT_EXPORT);

    @Test
    void exactlyTheD142PermissionsAreSensitive() {
        Set<Permission> sensitive = Arrays.stream(Permission.values())
                .filter(Permission::sensitive)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));

        assertThat(D142).hasSize(15);
        assertThat(sensitive).isEqualTo(D142);
    }
}
