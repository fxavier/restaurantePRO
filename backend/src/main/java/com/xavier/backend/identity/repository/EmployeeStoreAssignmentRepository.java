package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.EmployeeStoreAssignment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeStoreAssignmentRepository extends JpaRepository<EmployeeStoreAssignment, UUID> {

    List<EmployeeStoreAssignment> findByEmployeeId(UUID employeeId);

    List<EmployeeStoreAssignment> findByEmployeeIdAndStoreId(UUID employeeId, UUID storeId);

    boolean existsByEmployeeIdAndStoreIdAndRoleId(UUID employeeId, UUID storeId, UUID roleId);

    @Query("select distinct a.employeeId from EmployeeStoreAssignment a where a.roleId = :roleId")
    List<UUID> findEmployeeIdsWithRole(UUID roleId);
}
