package com.xavier.backend.audit.repository;

import com.xavier.backend.audit.model.AuditEvent;
import com.xavier.backend.audit.model.dto.AuditEventView;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Insert and read only, on purpose: no save-for-update, no delete (D18). Reads return {@link AuditEventView}
 * projections, never entities. Store visibility is a parameter pair: {@code allStores} (an installation-wide auditor
 * reading every store, store-less rows included) or else {@code stores}, the only stores whose rows match. Every other
 * filter matches everything when {@code null}.
 */
public interface AuditEventRepository extends Repository<AuditEvent, UUID> {

    String VIEW = "select new com.xavier.backend.audit.model.dto.AuditEventView(e.id, e.occurredAt, e.actorKind, "
            + "e.actorId, e.approverId, e.action, e.entityType, e.entityId, e.storeId, e.terminalId, e.reason, "
            + "e.details) ";
    String MATCHING = "from AuditEvent e where e.occurredAt >= :from and e.occurredAt < :to "
            + "and (:allStores = true or e.storeId in :stores) "
            + "and (:actorId is null or e.actorId = :actorId) "
            + "and (:actorKind is null or e.actorKind = :actorKind) "
            + "and (:action is null or e.action = :action) "
            + "and (:entityType is null or e.entityType = :entityType) "
            + "and (:entityId is null or e.entityId = :entityId) ";
    String OF_ENTITY = "from AuditEvent e where e.entityType = :entityType and e.entityId = :entityId "
            + "and (:allStores = true or e.storeId in :stores) ";
    String NEWEST_FIRST = "order by e.occurredAt desc, e.id desc";

    AuditEvent save(AuditEvent event);

    @Query(VIEW + "from AuditEvent e where e.id = :id")
    Optional<AuditEventView> findViewById(@Param("id") UUID id);

    @Query(value = VIEW + MATCHING + NEWEST_FIRST, countQuery = "select count(e) " + MATCHING)
    Page<AuditEventView> findMatching(@Param("from") Instant from, @Param("to") Instant to,
                                      @Param("allStores") boolean allStores, @Param("stores") Collection<UUID> stores,
                                      @Param("actorId") UUID actorId, @Param("actorKind") String actorKind,
                                      @Param("action") String action, @Param("entityType") String entityType,
                                      @Param("entityId") String entityId, Pageable pageable);

    @Query("select count(e) " + MATCHING)
    long countMatching(@Param("from") Instant from, @Param("to") Instant to, @Param("allStores") boolean allStores,
                       @Param("stores") Collection<UUID> stores, @Param("actorId") UUID actorId,
                       @Param("actorKind") String actorKind, @Param("action") String action,
                       @Param("entityType") String entityType, @Param("entityId") String entityId);

    /** The CSV export: consume it inside a transaction and close it. */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "1000"))
    @Query(VIEW + MATCHING + NEWEST_FIRST)
    Stream<AuditEventView> streamMatching(@Param("from") Instant from, @Param("to") Instant to,
                                          @Param("allStores") boolean allStores,
                                          @Param("stores") Collection<UUID> stores, @Param("actorId") UUID actorId,
                                          @Param("actorKind") String actorKind, @Param("action") String action,
                                          @Param("entityType") String entityType,
                                          @Param("entityId") String entityId);

    @Query(value = VIEW + OF_ENTITY + NEWEST_FIRST, countQuery = "select count(e) " + OF_ENTITY)
    Page<AuditEventView> findHistory(@Param("entityType") String entityType, @Param("entityId") String entityId,
                                     @Param("allStores") boolean allStores, @Param("stores") Collection<UUID> stores,
                                     Pageable pageable);
}
