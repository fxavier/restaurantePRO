package com.xavier.backend.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityTest;
import com.xavier.backend.identity.model.Role;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The seven profiles of spec §3 are seeded by Flyway (V3001). */
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
    void theWaiterOnlyViews() {
        assertThat(jdbc.sql("""
                        SELECT rp.permission FROM identity.role_permission rp JOIN identity.role r ON r.id = rp.role_id
                        WHERE r.name = 'Empregado de sala'""").query(String.class).list())
                .containsExactlyInAnyOrder("DINING_VIEW", "CATALOG_VIEW");
    }
}
