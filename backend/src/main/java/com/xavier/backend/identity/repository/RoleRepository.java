package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.Role;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    boolean existsByName(String name);

    /** The tenant's administrator role (row-level security leaves the bound tenant's only). */
    java.util.Optional<Role> findByAdministratorTrue();

    default java.util.Optional<Role> findAdministrator() {
        return findByAdministratorTrue();
    }

    /**
     * The tenant's administrator role, row-locked, to serialize checks about who holds it (the last administrator).
     * Row-level security leaves only the bound tenant's.
     */
    @Query(value = "SELECT id FROM identity.role WHERE administrator FOR UPDATE", nativeQuery = true)
    java.util.Optional<UUID> lockAdministrator();

    /** Row lock on a role, to serialize checks about who holds it (the last administrator). */
    @Query(value = "SELECT id FROM identity.role WHERE id = :id FOR UPDATE", nativeQuery = true)
    UUID lockRow(UUID id);
}
