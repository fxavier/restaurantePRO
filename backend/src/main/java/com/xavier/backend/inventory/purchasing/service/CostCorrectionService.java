package com.xavier.backend.inventory.purchasing.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.model.BalanceKey;
import com.xavier.backend.inventory.model.StockPosition;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.model.event.StockMovementsPosted;
import com.xavier.backend.inventory.purchasing.exception.PurchasingConflictException;
import com.xavier.backend.inventory.purchasing.exception.PurchasingRuleException;
import com.xavier.backend.inventory.purchasing.model.CostCorrection;
import com.xavier.backend.inventory.purchasing.model.CostVariance;
import com.xavier.backend.inventory.purchasing.model.GoodsReceipt;
import com.xavier.backend.inventory.purchasing.model.GoodsReceiptLine;
import com.xavier.backend.inventory.purchasing.model.ReceiptCostCorrection;
import com.xavier.backend.inventory.purchasing.model.ReceiptShare;
import com.xavier.backend.inventory.purchasing.model.dto.CostCorrectionRequest;
import com.xavier.backend.inventory.purchasing.model.dto.CostCorrectionView;
import com.xavier.backend.inventory.purchasing.repository.CostCorrectionRepository;
import com.xavier.backend.inventory.purchasing.repository.CostVarianceRepository;
import com.xavier.backend.inventory.purchasing.repository.GoodsReceiptRepository;
import com.xavier.backend.inventory.purchasing.repository.ShareQueries;
import com.xavier.backend.inventory.repository.LedgerStore;
import com.xavier.backend.inventory.service.BusinessDates;
import com.xavier.backend.inventory.service.StockLedger;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cost corrections of confirmed receipt lines (D125, P51): {@code GOODS_RECEIVE} in the receipt's store. A
 * correction never edits the receipt: it is its own fact, starting from the line's current cost (the previous
 * correction's, else the received one). The new cost is per the line's purchase unit (its pack, or the base unit).
 * Only the line's own share still in stock under the moving average ({@link ReceiptShare}, replayed from the ledger
 * under the balance lock) is revalued, by a REVALUATION (source COST_CORRECTION) through the ledger that never takes
 * the balance value below zero; everything else is a {@link CostVariance} for the margin report (D125 amended). {@code StockMovementsPosted(COST_CORRECTION, …)} is published either
 * way, by the ledger when it posted, else here, so reporting learns about a variance with nothing left to revalue.
 * Lock order: the receipt (serializing the corrections of its lines), then the balance row. Audited.
 */
@Service
public class CostCorrectionService {

    public static final String SOURCE_TYPE = "COST_CORRECTION";
    static final String ENTITY = "ReceiptCostCorrection";

    private final GoodsReceiptRepository receipts;
    private final GoodsReceiptService receiptService;
    private final CostCorrectionRepository corrections;
    private final CostVarianceRepository variances;
    private final StockLedger ledger;
    private final LedgerStore ledgerStore;
    private final ShareQueries shares;
    private final PurchaseUnits units;
    private final BusinessDates businessDates;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    CostCorrectionService(GoodsReceiptRepository receipts, GoodsReceiptService receiptService,
                          CostCorrectionRepository corrections, CostVarianceRepository variances, StockLedger ledger,
                          LedgerStore ledgerStore, ShareQueries shares, PurchaseUnits units,
                          BusinessDates businessDates, OrganizationApi organization,
                          AccessPolicy accessPolicy, AuditLog auditLog, ApplicationEventPublisher events,
                          Clock clock) {
        this.receipts = receipts;
        this.receiptService = receiptService;
        this.corrections = corrections;
        this.variances = variances;
        this.ledger = ledger;
        this.ledgerStore = ledgerStore;
        this.shares = shares;
        this.units = units;
        this.businessDates = businessDates;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @throws PurchasingRuleException RECEIPT_NOT_CONFIRMED, REASON_REQUIRED
     */
    @Transactional
    public CostCorrectionView correct(UUID receiptId, UUID lineId, CostCorrectionRequest request) {
        UUID storeId = receipts.refOf(receiptId)
                .orElseThrow(() -> new InventoryNotFoundException(GoodsReceiptService.ENTITY, receiptId)).storeId();
        accessPolicy.require(Permission.GOODS_RECEIVE, storeId);
        if (request.reason() == null || request.reason().isBlank()) {
            throw PurchasingRuleException.reasonRequired("cost correction");
        }
        GoodsReceipt receipt = receiptService.lock(receiptId);
        receipt.requireConfirmed();
        GoodsReceiptLine line = receipt.line(lineId)
                .orElseThrow(() -> new InventoryNotFoundException("GoodsReceiptLine", lineId));
        BigDecimal newCost = units.of(line.getStockItemId(), line.getPackId()).costPerBase(request.newUnitCost());
        PurchaseUnits.requireValue(line.getStockItemId(), line.getQuantityBase(), newCost);
        ReceiptCostCorrection previous = corrections.findFirstByReceiptLineIdOrderBySeqDesc(lineId).orElse(null);
        BigDecimal previousCost = previous == null ? line.getUnitCostBase() : previous.getNewUnitCost();
        BigDecimal received = line.getQuantityBase().subtract(receipts.returnedOf(lineId));

        BalanceKey key = new BalanceKey(receipt.getWarehouseId(), line.getStockItemId());
        ledger.lock(List.of(key));
        StockPosition position = ledgerStore.lock(List.of(key)).get(key); // held: the ledger locked it
        ShareQueries.ReceiptPoint point = shares.receiptPoint(receipt.getWarehouseId(), line.getStockItemId(),
                receiptId);
        BigDecimal share = ReceiptShare.of(line.getQuantityBase(), point.quantityAfter(), shares.stepsAfter(
                receipt.getWarehouseId(), line.getStockItemId(), point.postingSeq(), lineId));
        CostCorrection split = CostCorrection.split(received, share, position.value(), previousCost, newCost);

        Instant now = clock.instant();
        LocalDate day = businessDates.of(storeId, now);
        Actor actor = accessPolicy.current();
        ReceiptCostCorrection correction = UniqueConstraints.saveAndFlush(corrections, ReceiptCostCorrection.of(
                        receiptId, lineId, previous == null ? 1 : previous.getSeq() + 1, previousCost, newCost,
                        request.reason().strip(), split, day, actor.kind().name(),
                        actor.kind() == ActorKind.EMPLOYEE ? actor.employeeId() : actor.userId(), now),
                () -> PurchasingConflictException.correctionConcurrent(lineId), "receipt_cost_correction_seq_uk");
        if (split.revaluationValue().signum() != 0) {
            ledger.post(List.of(PostMovement.revaluation(receipt.getWarehouseId(), line.getStockItemId(),
                    split.revaluationValue(), SOURCE_TYPE, correction.getId(), day, now).withSourceLine(lineId)
                    .withReason(correction.getReason())));
        } else {
            events.publishEvent(new StockMovementsPosted(SOURCE_TYPE, correction.getId(), storeId, day));
        }
        if (split.varianceValue().signum() != 0) {
            variances.saveAndFlush(CostVariance.ofCorrection(storeId, receipt.getWarehouseId(),
                    line.getStockItemId(), day, split.varianceValue(),
                    organization.companyProfile().currency().getCurrencyCode(), correction.getId(), lineId));
        }
        auditLog.recordAsCurrentActor("RECEIPT_COST_CORRECTED", ENTITY, correction.getId().toString(), storeId, null,
                correction.getReason(), Map.of("receiptId", receiptId.toString(), "receiptLineId", lineId.toString(),
                        "previousUnitCost", previousCost.toPlainString(), "newUnitCost", newCost.toPlainString(),
                        "revaluationValue", split.revaluationValue().toPlainString(),
                        "varianceValue", split.varianceValue().toPlainString()));
        return view(correction);
    }

    @Transactional(readOnly = true)
    public List<CostCorrectionView> list(UUID receiptId) {
        UUID storeId = receipts.refOf(receiptId)
                .orElseThrow(() -> new InventoryNotFoundException(GoodsReceiptService.ENTITY, receiptId)).storeId();
        accessPolicy.requireAny(storeId, Permission.GOODS_RECEIVE, Permission.INVENTORY_VIEW);
        return corrections.findByReceiptIdOrderByAtAscSeqAsc(receiptId).stream().map(CostCorrectionService::view)
                .toList();
    }

    static CostCorrectionView view(ReceiptCostCorrection correction) {
        return new CostCorrectionView(correction.getId(), correction.getReceiptId(), correction.getReceiptLineId(),
                correction.getSeq(), correction.getPreviousUnitCost(), correction.getNewUnitCost(),
                correction.getReason(), correction.getRevaluedQuantity(), correction.getRevaluationValue(),
                correction.getVarianceValue(), correction.getBusinessDate(), correction.getAt());
    }
}
