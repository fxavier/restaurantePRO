package com.xavier.backend.orders.controller;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.model.dto.TableMoveRequest;
import com.xavier.backend.orders.model.dto.TransferRequest;
import com.xavier.backend.orders.model.dto.TransferView;
import com.xavier.backend.orders.model.dto.VoidLineRequest;
import com.xavier.backend.orders.model.dto.VoidView;
import com.xavier.backend.orders.service.Approval;
import com.xavier.backend.orders.service.LineVoidService;
import com.xavier.backend.orders.service.OrderTransferService;
import com.xavier.backend.orders.service.TableMoveService;
import com.xavier.backend.shared.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * POS adjustments of orders (PRD S14): voids, transfers, table moves, cancel and the subtotal printout. All are
 * idempotent; store and terminal come from the session (D39).
 */
@RestController
class PosOrderAdjustmentController {

    static final String ORDERS = PosOrderController.ORDERS;

    private final LineVoidService voids;
    private final OrderTransferService transfers;
    private final TableMoveService tableMoves;
    private final Idempotent idempotent;

    PosOrderAdjustmentController(LineVoidService voids, OrderTransferService transfers, TableMoveService tableMoves,
                                 IdempotencyService idempotency, AccessPolicy accessPolicy) {
        this.voids = voids;
        this.transfers = transfers;
        this.tableMoves = tableMoves;
        this.idempotent = new Idempotent(idempotency, accessPolicy);
    }

    /** D40: the approver is verified first, outside any transaction; its headers never enter the idempotency hash. */
    @PostMapping(ORDERS + "/{id}/voids")
    ResponseEntity<VoidView> voidLine(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                      @RequestHeader(value = "X-Approver-Code", required = false) String approverCode,
                                      @RequestHeader(value = "X-Approver-Pin", required = false) String approverPin,
                                      @Valid @RequestBody VoidLineRequest body, HttpServletRequest request) {
        Approval approval = voids.authorize(id, approverCode, approverPin);
        return idempotent.execute("orders.line.void", key, request, body, VoidView.class, () -> {
            VoidView voided = voids.voidLine(id, body, approval);
            return ResponseEntity.created(URI.create(ORDERS + "/" + id + "/voids/" + voided.id())).body(voided);
        });
    }

    @PostMapping(ORDERS + "/{id}/transfers")
    ResponseEntity<TransferView> transfer(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                          @Valid @RequestBody TransferRequest body, HttpServletRequest request) {
        return idempotent.execute("orders.lines.transfer", key, request, body, TransferView.class, () -> {
            TransferView transfer = transfers.transfer(id, body);
            return ResponseEntity.created(URI.create(ORDERS + "/" + id + "/transfers/" + transfer.id()))
                    .body(transfer);
        });
    }

    @PostMapping(ORDERS + "/{id}/table-move")
    ResponseEntity<OrderView> moveTable(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody TableMoveRequest body, HttpServletRequest request) {
        return idempotent.execute("orders.table.move", key, request, body, OrderView.class,
                () -> ResponseEntity.ok(tableMoves.move(id, body)));
    }
}
