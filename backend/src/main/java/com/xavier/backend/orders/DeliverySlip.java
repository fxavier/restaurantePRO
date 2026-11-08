package com.xavier.backend.orders;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The non-fiscal slip a courier carries (D129, P48): the customer, the address snapshot, the zone, what was promised,
 * the billable lines and what is due. {@code phone} is {@code null} when the customer has none.
 */
public record DeliverySlip(long orderNumber, String customerName, String phone, Address address, String zone,
                           Instant promisedAt, List<Line> lines, Money due) {

    public record Address(String line1, String line2, String city, String postalCode, String notes) {
    }

    public record Line(String description, BigDecimal quantity) {
    }
}
