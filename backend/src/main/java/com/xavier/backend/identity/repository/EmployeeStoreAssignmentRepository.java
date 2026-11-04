package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.EmployeeStoreAssignment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeStoreAssignmentRepository extends JpaRepository<EmployeeStoreAssignment, UUID> {

    List<EmployeeStoreAssignment> findByEmployeeId(UUID employeeId);

    List<EmployeeStoreAssignment> findByEmployeeIdAndStoreId(UUID employeeId, UUID storeId);

    boolean existsByEmployeeIdAndStoreIdAndRoleId(UUID employeeId, UUID storeId, UUID roleId);
}
