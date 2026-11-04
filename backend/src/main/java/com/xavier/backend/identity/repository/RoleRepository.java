package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.Role;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    boolean existsByNameIgnoreCase(String name);
}
