package com.xavier.backend.orders.delivery.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.orders.DeliveryStatus;
import com.xavier.backend.orders.delivery.model.dto.DeliveryView;
import com.xavier.backend.orders.delivery.repository.DeliveryRepository;
import com.xavier.backend.shared.web.PageResponse;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deliveries with their history (§4 "histórico de entregas"): the POS's board and the BackOffice's per-day list. */
@Service
public class DeliveryQueryService {

    /** What the POS board shows when no status is asked for: the deliveries still being worked on. */
    static final Set<DeliveryStatus> ACTIVE = EnumSet.of(DeliveryStatus.PENDING, DeliveryStatus.OUT_FOR_DELIVERY,
            DeliveryStatus.RETURNED);

    private final DeliveryRepository deliveries;
    private final DeliveryViews views;
    private final AccessPolicy accessPolicy;

    DeliveryQueryService(DeliveryRepository deliveries, DeliveryViews views, AccessPolicy accessPolicy) {
        this.deliveries = deliveries;
        this.views = views;
        this.accessPolicy = accessPolicy;
    }

    /** The session store's deliveries in {@code status}, or the active ones when it is null, oldest order first. */
    @Transactional(readOnly = true)
    public List<DeliveryView> board(DeliveryStatus status) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_VIEW);
        return views.of(deliveries.findByStoreIdAndStatusInOrderByOrderId(pos.storeId(),
                status == null ? ACTIVE : Set.of(status)));
    }

    /** The store's deliveries whose order's business day is in {@code [from, to]}, optionally of one courier. */
    @Transactional(readOnly = true)
    public PageResponse<DeliveryView> history(UUID storeId, LocalDate from, LocalDate to, UUID courierId,
                                              Pageable pageable) {
        accessPolicy.require(Permission.ORDER_VIEW, storeId);
        Pageable byOrder = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("orderId"));
        Page<com.xavier.backend.orders.delivery.model.Delivery> page = deliveries.findInDays(storeId, from, to,
                courierId, byOrder);
        return PageResponse.from(page.map(delivery -> views.of(delivery)));
    }
}
