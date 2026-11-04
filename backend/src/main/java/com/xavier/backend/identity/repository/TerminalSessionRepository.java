package com.xavier.backend.identity.repository;

import com.xavier.backend.identity.model.TerminalSession;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TerminalSessionRepository extends JpaRepository<TerminalSession, String> {

    List<TerminalSession> findByTerminalId(UUID terminalId);
}
