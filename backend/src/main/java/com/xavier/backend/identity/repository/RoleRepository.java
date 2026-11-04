package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.Role;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    boolean existsByName(String name);

    /** Row lock on a role, to serialize checks about who holds it (the last administrator). */
    @Query(value = "SELECT id FROM identity.role WHERE id = :id FOR UPDATE", nativeQuery = true)
    UUID lockRow(UUID id);
}
