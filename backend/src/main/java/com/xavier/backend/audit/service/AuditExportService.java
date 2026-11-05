package com.xavier.backend.audit.service;

import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.audit.exception.AuditQueryException;
import com.xavier.backend.audit.model.dto.AuditEventView;
import com.xavier.backend.audit.model.dto.AuditFilter;
import com.xavier.backend.audit.repository.AuditEventRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audited CSV export of audit rows (D82, spec §10 "autorização adicional para… exportações"). Two steps:
 * {@link #start} authorizes, counts and records {@code AUDIT_EXPORTED} in its own committed transaction, so an export
 * is never unaudited even if the download breaks; {@link #writeCsv} then streams the rows.
 */
@Service
public class AuditExportService {

    static final long MAX_ROWS = 100_000;
    static final String EXPORTED = "AUDIT_EXPORTED";
    private static final String HEADER = "id,occurredAt,actorKind,actorId,approverId,action,entityType,entityId,"
            + "storeId,terminalId,reason,details";
    private static final String LINE_END = "\r\n";

    private final AuditEventRepository events;
    private final AuditAccess access;
    private final AuditLog auditLog;

    AuditExportService(AuditEventRepository events, AuditAccess access, AuditLog auditLog) {
        this.events = events;
        this.access = access;
        this.auditLog = auditLog;
    }

    /**
     * Needs {@code AUDIT_VIEW} and {@code AUDIT_EXPORT}; the export covers the stores where both are held.
     *
     * @throws AuditQueryException {@code RANGE_TOO_LARGE}, or {@code EXPORT_TOO_LARGE} over 100 000 rows
     */
    @Transactional
    public AuditExport start(AuditFilter filter) {
        StoreVisibility visibility = access.visibleStores(filter.storeId(), Permission.AUDIT_VIEW,
                Permission.AUDIT_EXPORT);
        AuditCriteria criteria = new AuditCriteria(filter, visibility);
        long rows = criteria.count(events);
        if (rows > MAX_ROWS) {
            throw AuditQueryException.exportTooLarge(rows, MAX_ROWS);
        }
        auditLog.recordAsCurrentActor(EXPORTED, "AuditExport", UuidVersion7Strategy.INSTANCE.generateUuid(null)
                .toString(), filter.storeId(), null, null, details(filter, rows));
        return new AuditExport(criteria);
    }

    /** RFC 4180 CSV (CRLF, quoted where needed), newest first; a field that a spreadsheet would run is defused. */
    @Transactional(readOnly = true)
    public void writeCsv(AuditExport export, Writer out) {
        try (Stream<AuditEventView> rows = export.criteria.stream(events)) {
            out.write(HEADER + LINE_END);
            for (Iterator<AuditEventView> it = rows.iterator(); it.hasNext(); ) {
                out.write(line(it.next()));
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String line(AuditEventView row) {
        return String.join(",", field(row.id()), field(row.occurredAt()), field(row.actorKind()),
                field(row.actorId()), field(row.approverId()), field(row.action()), field(row.entityType()),
                field(row.entityId()), field(row.storeId()), field(row.terminalId()), field(row.reason()),
                field(row.details())) + LINE_END;
    }

    static String field(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text; // CSV injection: a spreadsheet would evaluate it as a formula
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static Map<String, Object> details(AuditFilter filter, long rows) {
        Map<String, Object> details = new LinkedHashMap<>();
        put(details, "storeId", filter.storeId());
        put(details, "from", filter.from());
        put(details, "to", filter.to());
        put(details, "actorId", filter.actorId());
        put(details, "actorKind", filter.actorKind());
        put(details, "action", filter.action());
        put(details, "entityType", filter.entityType());
        put(details, "entityId", filter.entityId());
        details.put("rows", rows);
        return details;
    }

    private static void put(Map<String, Object> details, String key, Object value) {
        if (value != null) {
            details.put(key, Objects.toString(value));
        }
    }
}
