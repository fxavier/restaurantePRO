package com.xavier.backend.identity.repository;

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

    /**
     * Row-locked so concurrent wrong PINs are all counted (the 5th locks). Matches {@code upper(code)}, as the unique
     * index does (V3017): {@code ab12} and {@code AB12} are the same employee, whatever case was stored.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e where upper(e.code) = upper(:code)")
    Optional<Employee> findByCodeForPinCheck(String code);

    @Query("""
            select e from Employee e where exists (select 1 from EmployeeStoreAssignment a
                where a.employeeId = e.id and a.storeId = :storeId)""")
    Page<Employee> findAssignedTo(UUID storeId, Pageable pageable);

    @Query("""
            select e from Employee e where e.active = true and exists (select 1 from EmployeeStoreAssignment a
                where a.employeeId = e.id and a.storeId = :storeId) order by e.name, e.code""")
    List<Employee> findActiveAssignedTo(UUID storeId);
}
