package com.xavier.backend.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityTest;
import com.xavier.backend.identity.model.Role;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The seven profiles of spec §3 are seeded by Flyway (V3001), with the defaults of D62 (V3005), D92 (V3010) and D134
 * (V3012).
 */
@IdentityTest
class RoleSeedTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void theSevenProfilesOfTheSpecAreSeeded() {
        assertThat(jdbc.sql("SELECT name FROM identity.role WHERE id::text LIKE '01900000-0000-7000-8000-0000000003%'")
                .query(String.class).list())
                .containsExactlyInAnyOrder("Administrador", "Gerente", "Empregado de sala", "Caixa", "Cozinha/bar",
                        "Gestor de stock/compras", "Contabilidade/auditor");
    }

    @Test
    void theAdministratorRoleHoldsEveryPermission() {
        // adding a Permission constant needs a migration that grants it to the administrator role
        assertThat(jdbc.sql("SELECT permission FROM identity.role_permission WHERE role_id = :id")
                .param("id", Role.ADMINISTRATOR_ID).query(String.class).list())
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(Permission.values()).map(Enum::name).toList());
    }

    @Test
    void theDefaultRolesHoldTheirR1D62AndD92Permissions() {
        assertThat(preR4PermissionsOf("Gerente")).containsExactlyInAnyOrder("TERMINAL_MANAGE", "EMPLOYEE_MANAGE",
                "DINING_MANAGE", "DINING_VIEW", "CATALOG_MANAGE", "CATALOG_PUBLISH", "CATALOG_VIEW", "AUDIT_VIEW",
                "ORDER_VIEW", "ORDER_EDIT", "ORDER_VOID", "ORDER_VOID_APPROVE", "ORDER_TRANSFER", "ORDER_CANCEL",
                "TABLE_LOCK", "PRODUCTION_VIEW", "PRODUCTION_OPERATE", "PRODUCTION_MANAGE", "PRODUCTION_SUPPRESS",
                "PAYMENT_COLLECT", "PAYMENT_RESOLVE", "CASH_OPERATE", "CASH_EXPENSE", "CASH_VIEW", "CASH_DAY_CLOSE",
                "CASH_CLOSING_APPROVE", "CASH_REGISTER_MANAGE", "FISCAL_DOCUMENT_VIEW", "FISCAL_DOCUMENT_REPRINT",
                "BILL_SPLIT", "CUSTOMER_VIEW", "CUSTOMER_MANAGE", "CREDIT_LIMIT_MANAGE", "CREDIT_SALE",
                "PAYMENT_REFUND", "PAYMENT_REFUND_APPROVE", "CASH_ADJUST", "CASH_DAY_REOPEN",
                "CASH_SESSION_FORCE_CLOSE", "RECONCILIATION_VIEW", "RECONCILIATION_MANAGE", "REPORT_VIEW",
                "REPORT_EXPORT");
        assertThat(preR4PermissionsOf("Empregado de sala")).containsExactlyInAnyOrder("DINING_VIEW", "CATALOG_VIEW",
                "ORDER_VIEW", "ORDER_EDIT", "ORDER_VOID", "ORDER_TRANSFER", "BILL_SPLIT");
        assertThat(preR4PermissionsOf("Caixa")).containsExactlyInAnyOrder("DINING_VIEW", "CATALOG_VIEW",
                "ORDER_VIEW", "ORDER_EDIT", "ORDER_VOID", "PAYMENT_COLLECT", "CASH_OPERATE", "CASH_VIEW",
                "FISCAL_DOCUMENT_VIEW", "FISCAL_DOCUMENT_REPRINT", "BILL_SPLIT", "CUSTOMER_VIEW", "CUSTOMER_MANAGE",
                "CREDIT_SALE", "PAYMENT_REFUND");
        assertThat(preR4PermissionsOf("Cozinha/bar")).containsExactlyInAnyOrder("CATALOG_VIEW",
                "PRODUCTION_VIEW", "PRODUCTION_OPERATE");
        assertThat(preR4PermissionsOf("Gestor de stock/compras")).containsExactlyInAnyOrder("CATALOG_VIEW");
        assertThat(preR4PermissionsOf("Contabilidade/auditor")).containsExactlyInAnyOrder("DINING_VIEW",
                "CATALOG_VIEW", "AUDIT_VIEW", "ORDER_VIEW", "CASH_VIEW", "FISCAL_DOCUMENT_VIEW", "CUSTOMER_VIEW", "RECONCILIATION_VIEW",
                "RECONCILIATION_MANAGE", "REPORT_VIEW", "REPORT_EXPORT", "AUDIT_EXPORT");
    }

    @Test
    void theDefaultRolesHoldTheirD134Permissions() {
        assertThat(r4PermissionsOf("Gerente")).containsExactlyInAnyOrder("PROMOTION_VIEW", "PROMOTION_MANAGE",
                "ORDER_DISCOUNT", "ORDER_DISCOUNT_APPROVE", "ORDER_OFFER", "CATALOG_AVAILABILITY", "LOYALTY_VIEW", "LOYALTY_ENROLL",
                "LOYALTY_REDEEM", "LOYALTY_ADJUST", "RESERVATION_VIEW", "RESERVATION_MANAGE", "DELIVERY_OPERATE",
                "DELIVERY_ZONE_MANAGE", "INVENTORY_VIEW", "INVENTORY_MANAGE", "INVENTORY_MOVE", "INVENTORY_TRANSFER",
                "INVENTORY_COUNT", "INVENTORY_COUNT_APPROVE", "RECIPE_MANAGE", "PURCHASE_MANAGE", "GOODS_RECEIVE");
        assertThat(r4PermissionsOf("Empregado de sala")).containsExactlyInAnyOrder("ORDER_DISCOUNT", "ORDER_OFFER",
                "LOYALTY_VIEW", "LOYALTY_REDEEM", "RESERVATION_VIEW", "RESERVATION_MANAGE");
        assertThat(r4PermissionsOf("Caixa")).containsExactlyInAnyOrder("ORDER_DISCOUNT", "ORDER_OFFER",
                "LOYALTY_VIEW", "LOYALTY_ENROLL", "LOYALTY_REDEEM", "RESERVATION_VIEW", "RESERVATION_MANAGE",
                "DELIVERY_OPERATE");
        assertThat(r4PermissionsOf("Cozinha/bar")).containsExactlyInAnyOrder("CATALOG_AVAILABILITY",
                "INVENTORY_MOVE");
        assertThat(r4PermissionsOf("Gestor de stock/compras")).containsExactlyInAnyOrder("INVENTORY_VIEW",
                "INVENTORY_MANAGE", "INVENTORY_MOVE", "INVENTORY_TRANSFER", "INVENTORY_COUNT", "RECIPE_MANAGE",
                "PURCHASE_MANAGE", "GOODS_RECEIVE");
        assertThat(r4PermissionsOf("Contabilidade/auditor")).containsExactlyInAnyOrder("PROMOTION_VIEW",
                "LOYALTY_VIEW", "INVENTORY_VIEW");
    }

    @Test
    void administratorOnlyPermissionsStayWithTheAdministrator() {
        // D62, D92, D134: an empty column means administrator only. Seeded roles only: tests of the shared database
        // create roles of their own (e.g. one per sensitive permission in StepUpTest).
        assertThat(jdbc.sql("""
                        SELECT r.name FROM identity.role_permission rp JOIN identity.role r ON r.id = rp.role_id
                        WHERE rp.permission IN ('PAYMENT_METHOD_MANAGE', 'FISCAL_MANAGE', 'REPORT_REBUILD',
                                                'SUPPORT_ACCESS_MANAGE', 'LOYALTY_PROGRAM_MANAGE',
                                                'INVENTORY_REBUILD')
                          AND r.id::text LIKE '01900000-0000-7000-8000-0000000003%'""")
                .query(String.class).list()).containsOnly("Administrador");
    }

    /** The role's permissions before R4 (D134 is checked on its own). */
    private List<String> preR4PermissionsOf(String role) {
        return permissionsOf(role).stream().filter(permission -> !R4.contains(permission)).toList();
    }

    /** The role's permissions among the D134 (R4) constants. */
    private List<String> r4PermissionsOf(String role) {
        return permissionsOf(role).stream().filter(R4::contains).toList();
    }

    private static final Set<String> R4 = Set.of("PROMOTION_VIEW", "PROMOTION_MANAGE",
            "ORDER_DISCOUNT", "ORDER_DISCOUNT_APPROVE", "ORDER_OFFER", "CATALOG_AVAILABILITY", "LOYALTY_VIEW",
            "LOYALTY_ENROLL", "LOYALTY_REDEEM", "LOYALTY_ADJUST", "LOYALTY_PROGRAM_MANAGE", "RESERVATION_VIEW",
            "RESERVATION_MANAGE", "DELIVERY_OPERATE", "DELIVERY_ZONE_MANAGE", "INVENTORY_VIEW", "INVENTORY_MANAGE",
            "INVENTORY_MOVE", "INVENTORY_TRANSFER", "INVENTORY_COUNT", "INVENTORY_COUNT_APPROVE", "RECIPE_MANAGE",
            "PURCHASE_MANAGE", "GOODS_RECEIVE", "INVENTORY_REBUILD");

    private List<String> permissionsOf(String role) {
        return jdbc.sql("""
                        SELECT rp.permission FROM identity.role_permission rp JOIN identity.role r ON r.id = rp.role_id
                        WHERE r.id::text LIKE '01900000-0000-7000-8000-0000000003%' AND r.name = :name""")
                .param("name", role).query(String.class).list();
    }
}
