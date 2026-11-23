package com.xavier.backend.identity.repository;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.model.Employee;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    boolean existsByCodeIgnoreCase(String code);

    /** Row-locked so concurrent wrong PINs are all counted (the 5th locks). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e where e.code = :code")
    Optional<Employee> findByCodeForPinCheck(String code);

    @Query("""
            select e from Employee e where exists (select 1 from EmployeeStoreAssignment a
                where a.employeeId = e.id and a.storeId = :storeId)""")
    Page<Employee> findAssignedTo(UUID storeId, Pageable pageable);

    /** The active employees whose role in {@code storeId} holds {@code permission}, by name. */
    @Query("""
            select e from Employee e where e.active = true and exists (select 1 from EmployeeStoreAssignment a, Role r
                where a.employeeId = e.id and a.storeId = :storeId and r.id = a.roleId
                  and :permission member of r.permissions) order by e.name, e.code""")
    List<Employee> findActiveHolding(UUID storeId, Permission permission);

    @Query("""
            select count(e) > 0 from Employee e where e.id = :employeeId and e.active = true
              and exists (select 1 from EmployeeStoreAssignment a, Role r
                where a.employeeId = e.id and a.storeId = :storeId and r.id = a.roleId
                  and :permission member of r.permissions)""")
    boolean isActiveHolding(UUID employeeId, UUID storeId, Permission permission);
}
