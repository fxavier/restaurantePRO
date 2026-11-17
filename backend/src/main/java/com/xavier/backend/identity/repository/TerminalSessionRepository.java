package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.TerminalSession;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TerminalSessionRepository extends JpaRepository<TerminalSession, String> {

    @Modifying
    @Query("delete from TerminalSession s where s.terminalId = :terminalId")
    int deleteByTerminal(UUID terminalId);

    @Modifying
    @Query("delete from TerminalSession s where s.storeId = :storeId")
    int deleteByStore(UUID storeId);

    @Modifying
    @Query("delete from TerminalSession s where s.employeeId = :employeeId")
    int deleteByEmployee(UUID employeeId);
}
