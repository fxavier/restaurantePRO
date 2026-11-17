package com.xavier.backend.identity.service;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.model.Role;
import com.xavier.backend.identity.repository.RoleRepository;
import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The standard roles of a tenant (D181): a provisioned tenant starts with the roles the identity migrations seed,
 * copied from their template ({@code identity.role_template}, a snapshot V3016 took of the seeded roles; PLATFORM,
 * read-only for the application role), so the migrations stay the one source of the set.
 */
@Component
class StandardRoles {

    private record Template(String name, boolean administrator, Set<Permission> permissions) {
    }

    private final JdbcClient jdbc;
    private final RoleRepository roles;
    private final Clock clock;

    StandardRoles(JdbcClient jdbc, RoleRepository roles, Clock clock) {
        this.jdbc = jdbc;
        this.roles = roles;
        this.clock = clock;
    }

    /**
     * Creates the bound tenant's standard roles, inside the caller's transaction, unless it has its administrator
     * role already (an earlier delivery created them all, atomically). @return the administrator role
     */
    Role ensureFor() {
        return roles.findAdministrator().orElseGet(() -> {
            Role administrator = null;
            for (Template template : templates()) {
                Role role = roles.saveAndFlush(Role.fromTemplate(template.name(), template.permissions(),
                        template.administrator(), clock.instant()));
                if (template.administrator()) {
                    administrator = role;
                }
            }
            if (administrator == null) {
                throw new IllegalStateException("identity.role_template holds no administrator role");
            }
            return administrator;
        });
    }

    private List<Template> templates() {
        Map<UUID, Template> byId = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name, administrator FROM identity.role_template ORDER BY name")
                .query((rs, row) -> byId.put(rs.getObject("id", UUID.class), new Template(rs.getString("name"),
                        rs.getBoolean("administrator"), EnumSet.noneOf(Permission.class))))
                .list();
        jdbc.sql("SELECT template_id, permission FROM identity.role_template_permission")
                .query((rs, row) -> byId.get(rs.getObject("template_id", UUID.class)).permissions()
                        .add(Permission.valueOf(rs.getString("permission"))))
                .list();
        return List.copyOf(byId.values());
    }
}
