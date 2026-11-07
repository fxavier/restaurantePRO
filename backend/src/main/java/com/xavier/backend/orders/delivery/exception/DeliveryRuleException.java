package com.xavier.backend.orders.delivery.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.UUID;

/**
 * A delivery business rule refuses the request (422, D127, D128). The codes are stable: clients switch on them.
 */
public class DeliveryRuleException extends BusinessRuleException {

    private DeliveryRuleException(String code, String detail) {
        super(code, detail);
    }

    /** The zone's fee item is not a FEE item of the store's published catalog (D128). */
    public static DeliveryRuleException feeItemNotPublished(String zoneCode) {
        return new DeliveryRuleException("FEE_ITEM_NOT_PUBLISHED",
                "The fee item of delivery zone " + zoneCode + " is not a fee in the published catalog.");
    }

    public static DeliveryRuleException zoneInactive(String zoneCode) {
        return new DeliveryRuleException("ZONE_INACTIVE", "Delivery zone " + zoneCode + " is inactive.");
    }

    public static DeliveryRuleException zoneOtherStore(UUID zoneId) {
        return new DeliveryRuleException("ZONE_OTHER_STORE", "Delivery zone " + zoneId + " is not in this store.");
    }

    /** A DELIVERY order needs its customer and delivery details (D127, D130). */
    public static DeliveryRuleException customerRequired() {
        return new DeliveryRuleException("CUSTOMER_REQUIRED",
                "A DELIVERY order is opened with its customer, address and zone.");
    }

    public static DeliveryRuleException hasDrafts(UUID orderId) {
        return new DeliveryRuleException("DELIVERY_HAS_DRAFTS",
                "Order " + orderId + " has draft lines: confirm or delete them before the dispatch.");
    }

    public static DeliveryRuleException nothingConfirmed(UUID orderId) {
        return new DeliveryRuleException("DELIVERY_NOTHING_CONFIRMED",
                "Order " + orderId + " has no confirmed round to deliver.");
    }

    public static DeliveryRuleException courierNotInStore(UUID employeeId) {
        return new DeliveryRuleException("COURIER_NOT_IN_STORE",
                "Employee " + employeeId + " is not an active employee of this store.");
    }

    public static DeliveryRuleException notDelivered(UUID orderId) {
        return new DeliveryRuleException("DELIVERY_NOT_DELIVERED",
                "Order " + orderId + " is closed only once its delivery is DELIVERED.");
    }

    public static DeliveryRuleException inProgress(UUID orderId) {
        return new DeliveryRuleException("DELIVERY_IN_PROGRESS",
                "The delivery of order " + orderId + " is out or delivered: it cannot be cancelled.");
    }

    /** P48: a delivery order's fee draft changes only by a zone swap, a discount or an offer. */
    public static DeliveryRuleException feeLineLocked(UUID lineId) {
        return new DeliveryRuleException("FEE_LINE_LOCKED", "Line " + lineId
                + " is the delivery fee: change the zone, or give a discount or an offer, instead.");
    }

    /** An order never carries two fees: the confirmed one is voided before the zone changes. */
    public static DeliveryRuleException zoneFeeConfirmed(UUID orderId) {
        return new DeliveryRuleException("ZONE_FEE_CONFIRMED",
                "The delivery fee is already confirmed: void it before changing the zone.");
    }

    public static DeliveryRuleException addressFrozen(UUID orderId) {
        return new DeliveryRuleException("ADDRESS_FROZEN",
                "The delivery of order " + orderId + " was dispatched: its address, zone and promise are frozen.");
    }
}
