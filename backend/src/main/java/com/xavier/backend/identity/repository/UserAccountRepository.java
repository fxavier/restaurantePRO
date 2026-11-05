package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.UserAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<UserAccount> findByEmployeeId(UUID employeeId);

    /** Support accounts do not count: their sessions never hold the access-managing permissions (D83). */
    @Query("select count(a) from UserAccount a join a.roleIds r "
            + "where r = :roleId and a.active = true and a.support = false and a.id <> :excluded")
    long countActiveNonSupportHoldersExcept(UUID roleId, UUID excluded);

    @Query("select a.id from UserAccount a join a.roleIds r where r = :roleId")
    List<UUID> findIdsHoldingRole(UUID roleId);
}
