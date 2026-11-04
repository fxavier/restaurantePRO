package com.xavier.backend.audit.repository;

import com.xavier.backend.audit.model.AuditEvent;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Insert-only on purpose: no save-for-update, no delete (D18). Queries arrive with R3. */
public interface AuditEventRepository extends Repository<AuditEvent, UUID> {

    AuditEvent save(AuditEvent event);
}
