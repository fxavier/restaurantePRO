package com.xavier.backend.settlement.reconciliation.service;

import com.xavier.backend.settlement.reconciliation.model.EntryKind;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationBatch;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationEntry;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationResolution;
import com.xavier.backend.settlement.reconciliation.model.dto.BatchView;
import com.xavier.backend.settlement.reconciliation.model.dto.EntryView;
import com.xavier.backend.settlement.reconciliation.model.dto.ResolutionView;
import com.xavier.backend.settlement.reconciliation.repository.KindCount;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps reconciliation facts to their views (entities never leave the module's services). */
final class ReconciliationViews {

    private ReconciliationViews() {
    }

    /** {@code counts} are the batch's entries per kind with how many are open. */
    static BatchView batch(ReconciliationBatch batch, Collection<KindCount> counts) {
        Map<String, Long> perKind = new LinkedHashMap<>();
        long open = 0;
        for (EntryKind kind : EntryKind.values()) {
            for (KindCount count : counts) {
                if (count.kind() == kind) {
                    perKind.merge(kind.name(), count.count(), Long::sum);
                    open += count.open();
                }
            }
        }
        return new BatchView(batch.getId(), batch.getStoreId(), batch.getMethodCode(), batch.getPeriodFrom(),
                batch.getPeriodTo(), batch.getSource().name(), batch.getFileSha256(), batch.getLineCount(),
                batch.getImportedByAccountId(), batch.getImportedAt(), perKind, open);
    }

    static EntryView entry(ReconciliationEntry entry, Currency currency, ReconciliationResolution resolution) {
        return new EntryView(entry.getId(), entry.getBatchId(), entry.getLineNo(), entry.getKind().name(),
                entry.getPaymentId(), entry.getProviderReference(), entry.getAuthorizationCode(), entry.getLineRef(),
                entry.getOccurredAt(), money(entry.getInternalAmount(), currency),
                money(entry.getProviderAmount(), currency), money(entry.getFeeAmount(), currency),
                entry.difference(currency), resolution == null ? null : resolution(resolution));
    }

    static ResolutionView resolution(ReconciliationResolution resolution) {
        return new ResolutionView(resolution.getId(), resolution.getEntryId(), resolution.getAction().name(),
                resolution.getReason(), resolution.getActorKind(), resolution.getActorId(), resolution.getAt());
    }

    static List<KindCount> counted(ReconciliationBatch batch, List<ReconciliationEntry> entries) {
        Map<EntryKind, long[]> counts = new LinkedHashMap<>();
        for (ReconciliationEntry entry : entries) {
            long[] count = counts.computeIfAbsent(entry.getKind(), kind -> new long[2]);
            count[0]++;
            if (entry.getKind() != EntryKind.MATCHED) {
                count[1]++; // a MATCHED entry is resolved AUTO_MATCHED in the same transaction
            }
        }
        return counts.entrySet().stream()
                .map(e -> new KindCount(batch.getId(), e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
    }

    private static Money money(BigDecimal amount, Currency currency) {
        return amount == null ? null : Money.of(amount, currency);
    }
}
