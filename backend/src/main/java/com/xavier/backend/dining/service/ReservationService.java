package com.xavier.backend.dining.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.dining.exception.DiningNotFoundException;
import com.xavier.backend.dining.exception.InvalidDiningSetupException;
import com.xavier.backend.dining.exception.ReservationRuleException;
import com.xavier.backend.dining.model.Reservation;
import com.xavier.backend.dining.model.ReservationDetails;
import com.xavier.backend.dining.model.RestaurantTable;
import com.xavier.backend.dining.model.dto.ReservationCancelRequest;
import com.xavier.backend.dining.model.dto.ReservationRequest;
import com.xavier.backend.dining.model.dto.ReservationUpdateRequest;
import com.xavier.backend.dining.model.dto.ReservationView;
import com.xavier.backend.dining.repository.ReservationRepository;
import com.xavier.backend.dining.repository.RestaurantTableRepository;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.StoreProbe;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reservation calendar and its POS commands (spec §4, D126, P47). Reservations are informative: there is no
 * overlap or capacity check, and nothing here touches occupations or locks. POS commands take the store from the
 * session (D39); a reservation addressed by id is authorized on its store before it is locked (D42). Seating
 * happens through {@code DiningApi.openOccupation(…, reservationId)}.
 */
@Service
public class ReservationService {

    static final String ENTITY = "Reservation";
    static final long MAX_RANGE_DAYS = 92;

    private final ReservationRepository reservations;
    private final RestaurantTableRepository tables;
    private final CustomersApi customers;
    private final DiningSettingsService settings;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final StoreProbe probe;
    private final DiningRows rows;
    private final DiningChanges changes;
    private final Clock clock;

    ReservationService(ReservationRepository reservations, RestaurantTableRepository tables, CustomersApi customers,
                       DiningSettingsService settings,
                       OrganizationApi organization, AccessPolicy accessPolicy, StoreProbe probe, DiningRows rows,
                       DiningChanges changes, Clock clock) {
        this.reservations = reservations;
        this.tables = tables;
        this.customers = customers;
        this.settings = settings;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.probe = probe;
        this.rows = rows;
        this.changes = changes;
        this.clock = clock;
    }

    /** The POS calendar: the session store's reservations (every state) on {@code date} in the store's zone. */
    @Transactional(readOnly = true)
    public List<ReservationView> day(LocalDate date) {
        UUID storeId = accessPolicy.requirePos(Permission.RESERVATION_VIEW).storeId();
        return between(storeId, date, date);
    }

    /** The BackOffice calendar: {@code from} to {@code to}, both inclusive, at most 92 days. */
    @Transactional(readOnly = true)
    public List<ReservationView> range(UUID storeId, LocalDate from, LocalDate to) {
        accessPolicy.require(Permission.RESERVATION_VIEW, storeId);
        StoreChecks.requireExisting(organization, storeId);
        if (from.isAfter(to)) {
            throw ReservationRuleException.reversed(from, to);
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw ReservationRuleException.tooLarge(MAX_RANGE_DAYS);
        }
        return between(storeId, from, to);
    }

    @Transactional
    public ReservationView book(ReservationRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.RESERVATION_MANAGE);
        ReservationDetails details = details(pos.storeId(), request.startsAt(), request.durationMinutes(),
                request.partySize(), request.contactName(), request.contactPhone(), request.customerId(),
                request.notes(), request.tableIds());
        Actor actor = accessPolicy.current();
        Reservation reservation = reservations.saveAndFlush(
                Reservation.book(pos.storeId(), details, actor.kind().name(), actor.userId(), clock.instant()));
        changed("RESERVATION_BOOKED", reservation, Map.of());
        return reservation.toView();
    }

    @Transactional
    public ReservationView update(UUID id, ReservationUpdateRequest request) {
        return command(id, request.version(), (reservation, pos) -> {
            reservation.update(details(pos.storeId(), request.startsAt(), request.durationMinutes(),
                    request.partySize(), request.contactName(), request.contactPhone(), request.customerId(),
                    request.notes(), request.tableIds()), clock.instant());
            return "RESERVATION_UPDATED";
        });
    }

    @Transactional
    public ReservationView cancel(UUID id, ReservationCancelRequest request) {
        return command(id, request.version(), (reservation, pos) -> {
            reservation.cancel(request.reason(), clock.instant());
            return "RESERVATION_CANCELLED";
        });
    }

    /** P47: staff may mark a no-show once the start has passed (422 {@code RESERVATION_NOT_STARTED} before). */
    @Transactional
    public ReservationView markNoShow(UUID id, long version) {
        return command(id, version, (reservation, pos) -> {
            reservation.markNoShow(accessPolicy.current().kind().name(), clock.instant());
            return "RESERVATION_NO_SHOW";
        });
    }

    private interface Command {
        /** Applies the change and returns the audit action. */
        String apply(Reservation reservation, PosContext pos);
    }

    /** D42: the store only (404), the session's store (403), then the row lock, the version (409) and the change. */
    private ReservationView command(UUID id, long version, Command command) {
        PosContext pos = accessPolicy.requirePos(Permission.RESERVATION_MANAGE);
        UUID storeId = probe.storeOf(ReservationRepository.TABLE, id)
                .orElseThrow(() -> new DiningNotFoundException(ENTITY, id));
        accessPolicy.requireSessionStore(pos, storeId, ENTITY, id);
        Reservation reservation = rows.lockReservation(id);
        OptimisticLocking.requireVersion(Reservation.class, id, reservation.getVersion(), version);
        String action = command.apply(reservation, pos);
        reservations.saveAndFlush(reservation); // the response carries the new version
        changed(action, reservation, Map.of());
        return reservation.toView();
    }

    private List<ReservationView> between(UUID storeId, LocalDate from, LocalDate to) {
        ZoneId zone = organization.zoneOf(storeId);
        return reservations.findByStoreIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAscIdAsc(
                        storeId, from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
                .stream().map(Reservation::toView).toList();
    }

    private ReservationDetails details(UUID storeId, Instant startsAt, Integer durationMinutes, Integer partySize,
                                       String contactName, String contactPhone, UUID customerId, String notes,
                                       List<UUID> tableIds) {
        if (settings.windowOf(storeId).noShowDue(startsAt, clock.instant())) {
            throw ReservationRuleException.inThePast(startsAt); // the job would mark it a no-show at once
        }
        Set<UUID> tableSet = tableIds == null ? Set.of() : new LinkedHashSet<>(tableIds);
        requireTablesOf(storeId, tableSet);
        if (customerId != null && customers.findCustomer(customerId).isEmpty()) {
            throw ReservationRuleException.customerNotFound(customerId);
        }
        return new ReservationDetails(startsAt, durationMinutes, partySize, contactName, phoneDigits(contactPhone),
                customerId, notes, tableSet);
    }

    /** An unknown table is "not in this store" as well: the tables a reservation names are the store's. */
    private void requireTablesOf(UUID storeId, Set<UUID> tableIds) {
        Map<UUID, UUID> storeByTable = tables.findAllById(tableIds).stream()
                .collect(Collectors.toMap(RestaurantTable::getId, RestaurantTable::getStoreId));
        for (UUID tableId : tableIds) {
            if (!storeId.equals(storeByTable.get(tableId))) {
                throw ReservationRuleException.tableOtherStore(tableId);
            }
        }
    }

    /** The DB keeps digits only (4–20); separators a host types are dropped. */
    static String phoneDigits(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (!digits.matches("[0-9]{4,20}")) {
            throw new InvalidDiningSetupException("contactPhone must have 4 to 20 digits");
        }
        return digits;
    }

    private void changed(String action, Reservation reservation, Map<String, Object> extra) {
        Map<String, Object> details = new HashMap<>(extra);
        details.put("startsAt", reservation.getStartsAt().toString());
        details.put("partySize", reservation.getPartySize());
        details.put("status", reservation.getStatus().name());
        details.put("tables", reservation.getTableIds().size());
        changes.record(action, ENTITY, reservation.getId(), reservation.getStoreId(), details);
    }
}
