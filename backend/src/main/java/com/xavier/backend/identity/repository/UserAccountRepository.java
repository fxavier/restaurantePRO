package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);

    @Query("select count(a) from UserAccount a join a.roleIds r "
            + "where r = :roleId and a.active = true and a.id <> :excluded")
    long countActiveHoldersExcept(UUID roleId, UUID excluded);
}
