package com.xavier.backend.cash.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.exception.CashNotFoundException;
import com.xavier.backend.cash.model.CashClosing;
import com.xavier.backend.cash.model.CashClosingLine;
import com.xavier.backend.cash.model.CashDay;
import com.xavier.backend.cash.model.dto.ReconciliationLine;
import com.xavier.backend.cash.repository.CashClosingRepository;
import com.xavier.backend.cash.repository.CashDayRepository;
import com.xavier.backend.cash.repository.CashMovementRepository;
import com.xavier.backend.cash.repository.CashSessionCountRepository;
import com.xavier.backend.cash.repository.DayRegisterMethodCount;
import com.xavier.backend.cash.repository.DayRegisterMethodTotal;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cash reconciliation (D79 (7), D87): the CashClosing is the reconciliation (expected Σ movements against
 * counted, §8.1 "Fecho"), so this reads the movements, counts and closings themselves and copies nothing.
 */
@Service
public class CashReconciliationService {

    private final CashDayRepository days;
    private final CashMovementRepository movements;
    private final CashSessionCountRepository counts;
    private final CashClosingRepository closings;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;

    CashReconciliationService(CashDayRepository days, CashMovementRepository movements,
                              CashSessionCountRepository counts, CashClosingRepository closings,
                              OrganizationApi organization, AccessPolicy accessPolicy) {
        this.days = days;
        this.movements = movements;
        this.counts = counts;
        this.closings = closings;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
    }

    /** {@code CASH_VIEW} in the store; the store's days with {@code from ≤ businessDate ≤ to}, in date order. */
    @Transactional(readOnly = true)
    public List<ReconciliationLine> reconcile(UUID storeId, LocalDate from, LocalDate to) {
        accessPolicy.require(Permission.CASH_VIEW, storeId);
        Map<UUID, CashDay> dayById = days.findByStoreIdAndBusinessDateBetweenOrderByBusinessDateAsc(storeId, from, to)
                .stream().collect(Collectors.toMap(CashDay::getId, Function.identity()));
        if (dayById.isEmpty()) {
            return List.of();
        }
        Currency currency = organization.findStore(storeId)
                .orElseThrow(() -> new CashNotFoundException("Store", storeId)).currency();
        Map<Key, BigDecimal[]> amounts = new TreeMap<>(Key.order(dayById));
        for (DayRegisterMethodTotal total : movements.totalsByDayRegisterAndMethod(dayById.keySet())) {
            BigDecimal[] row = row(amounts, new Key(total.cashDayId(), total.registerId(), total.methodCode()));
            row[0] = total.expected();
            row[2] = total.adjustments();
        }
        for (DayRegisterMethodCount count : counts.countedByDayRegisterAndMethod(dayById.keySet())) {
            row(amounts, new Key(count.cashDayId(), count.registerId(), count.methodCode()))[1] = count.counted();
        }
        Map<List<UUID>, CashClosing> latestClosing = closings.findByCashDayIdIn(dayById.keySet()).stream()
                .collect(Collectors.toMap(c -> List.of(c.getCashDayId(), c.getRegisterId()), Function.identity(),
                        (a, b) -> LATEST.compare(a, b) >= 0 ? a : b));
        List<ReconciliationLine> lines = new ArrayList<>();
        amounts.forEach((key, row) -> {
            CashDay day = dayById.get(key.dayId());
            Optional<CashClosing> closing = Optional.ofNullable(latestClosing.get(List.of(key.dayId(),
                    key.registerId())));
            Money expected = Money.of(row[0], currency);
            Money counted = Money.of(row[1], currency);
            lines.add(new ReconciliationLine(day.getId(), day.getBusinessDate(), day.getStatus().name(),
                    key.registerId(), key.methodCode(), expected, counted, Money.of(row[2], currency),
                    counted.minus(expected),
                    closing.flatMap(c -> c.getLines().stream().filter(l -> l.getMethodCode().equals(key.methodCode()))
                            .findFirst()).map(CashClosingLine::getJustification).orElse(null),
                    closing.map(CashClosing::getId).orElse(null),
                    closing.map(c -> c.getStatus().name()).orElse(null),
                    closing.map(CashClosing::getDayRound).orElse(null)));
        });
        return List.copyOf(lines);
    }

    private static final Comparator<CashClosing> LATEST = Comparator.comparingInt(CashClosing::getDayRound)
            .thenComparingInt(CashClosing::getAttemptNo);

    /** expected, counted, adjustments; zero until a query fills them. */
    private static BigDecimal[] row(Map<Key, BigDecimal[]> amounts, Key key) {
        return amounts.computeIfAbsent(key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
    }

    private record Key(UUID dayId, UUID registerId, String methodCode) {

        static Comparator<Key> order(Map<UUID, CashDay> dayById) {
            return Comparator.comparing((Key k) -> dayById.get(k.dayId()).getBusinessDate())
                    .thenComparing(Key::registerId).thenComparing(Key::methodCode);
        }
    }
}
