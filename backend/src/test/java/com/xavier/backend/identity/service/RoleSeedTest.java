package com.xavier.backend.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityTest;
import com.xavier.backend.identity.model.Role;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The seven profiles of spec §3 are seeded by Flyway (V3001), with the defaults of D62 (V3005) and D92 (V3010). */
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
        assertThat(permissionsOf("Gerente")).containsExactlyInAnyOrder("TERMINAL_MANAGE", "EMPLOYEE_MANAGE",
                "DINING_MANAGE", "DINING_VIEW", "CATALOG_MANAGE", "CATALOG_PUBLISH", "CATALOG_VIEW", "AUDIT_VIEW",
                "ORDER_VIEW", "ORDER_EDIT", "ORDER_VOID", "ORDER_VOID_APPROVE", "ORDER_TRANSFER", "ORDER_CANCEL",
                "TABLE_LOCK", "PRODUCTION_VIEW", "PRODUCTION_OPERATE", "PRODUCTION_MANAGE", "PRODUCTION_SUPPRESS",
                "PAYMENT_COLLECT", "PAYMENT_RESOLVE", "CASH_OPERATE", "CASH_EXPENSE", "CASH_VIEW", "CASH_DAY_CLOSE",
                "CASH_CLOSING_APPROVE", "CASH_REGISTER_MANAGE", "FISCAL_DOCUMENT_VIEW", "FISCAL_DOCUMENT_REPRINT",
                "BILL_SPLIT", "CUSTOMER_VIEW", "CUSTOMER_MANAGE", "CREDIT_LIMIT_MANAGE", "CREDIT_SALE",
                "PAYMENT_REFUND", "PAYMENT_REFUND_APPROVE", "CASH_ADJUST", "CASH_DAY_REOPEN",
                "CASH_SESSION_FORCE_CLOSE", "RECONCILIATION_VIEW", "RECONCILIATION_MANAGE", "REPORT_VIEW",
                "REPORT_EXPORT");
        assertThat(permissionsOf("Empregado de sala")).containsExactlyInAnyOrder("DINING_VIEW", "CATALOG_VIEW",
                "ORDER_VIEW", "ORDER_EDIT", "ORDER_VOID", "ORDER_TRANSFER", "BILL_SPLIT");
        assertThat(permissionsOf("Caixa")).containsExactlyInAnyOrder("DINING_VIEW", "CATALOG_VIEW",
                "ORDER_VIEW", "ORDER_EDIT", "ORDER_VOID", "PAYMENT_COLLECT", "CASH_OPERATE", "CASH_VIEW",
                "FISCAL_DOCUMENT_VIEW", "FISCAL_DOCUMENT_REPRINT", "BILL_SPLIT", "CUSTOMER_VIEW", "CUSTOMER_MANAGE",
                "CREDIT_SALE", "PAYMENT_REFUND");
        assertThat(permissionsOf("Cozinha/bar")).containsExactlyInAnyOrder("CATALOG_VIEW",
                "PRODUCTION_VIEW", "PRODUCTION_OPERATE");
        assertThat(permissionsOf("Gestor de stock/compras")).containsExactlyInAnyOrder("CATALOG_VIEW");
        assertThat(permissionsOf("Contabilidade/auditor")).containsExactlyInAnyOrder("DINING_VIEW", "CATALOG_VIEW",
                "AUDIT_VIEW", "ORDER_VIEW", "CASH_VIEW", "FISCAL_DOCUMENT_VIEW", "CUSTOMER_VIEW", "RECONCILIATION_VIEW",
                "RECONCILIATION_MANAGE", "REPORT_VIEW", "REPORT_EXPORT", "AUDIT_EXPORT");
    }

    @Test
    void administratorOnlyPermissionsStayWithTheAdministrator() {
        // D62, D92: an empty column means administrator only
        assertThat(jdbc.sql("""
                        SELECT r.name FROM identity.role_permission rp JOIN identity.role r ON r.id = rp.role_id
                        WHERE rp.permission IN ('PAYMENT_METHOD_MANAGE', 'FISCAL_MANAGE', 'REPORT_REBUILD',
                                                'SUPPORT_ACCESS_MANAGE')""")
                .query(String.class).list()).containsOnly("Administrador");
    }

    private java.util.List<String> permissionsOf(String role) {
        return jdbc.sql("""
                        SELECT rp.permission FROM identity.role_permission rp JOIN identity.role r ON r.id = rp.role_id
                        WHERE r.id::text LIKE '01900000-0000-7000-8000-0000000003%' AND r.name = :name""")
                .param("name", role).query(String.class).list();
    }
}
