package com.xavier.backend.orders;

import com.xavier.backend.orders.model.event.LineDiscountsRecorded;
import com.xavier.backend.orders.model.event.OrderBatchConfirmed;
import com.xavier.backend.orders.model.event.OrderLinesVoided;
import com.xavier.backend.orders.model.event.SubtotalRequested;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * Stands in for production (S15): an {@code @ApplicationModuleListener}, so the Modulith registry persists a
 * publication of every {@link OrderBatchConfirmed}, and it records what it received. It can also fail the publishing
 * transaction for one order (a synchronous listener throwing inside it), to prove the round and its publication
 * commit or roll back together.
 */
@TestComponent
public class OrderEventProbe {

    private final List<OrderBatchConfirmed> received = new CopyOnWriteArrayList<>();
    private final List<OrderLinesVoided> voids = new CopyOnWriteArrayList<>();
    private final List<SubtotalRequested> subtotals = new CopyOnWriteArrayList<>();
    private final List<LineDiscountsRecorded> discounts = new CopyOnWriteArrayList<>();
    private final List<com.xavier.backend.orders.model.event.DeliveryDispatched> dispatches =
            new CopyOnWriteArrayList<>();
    private final Set<UUID> failing = ConcurrentHashMap.newKeySet();

    @ApplicationModuleListener
    void on(OrderBatchConfirmed event) {
        received.add(event);
    }

    @ApplicationModuleListener
    void on(OrderLinesVoided event) {
        voids.add(event);
    }

    @ApplicationModuleListener
    void on(SubtotalRequested event) {
        subtotals.add(event);
    }

    @ApplicationModuleListener
    void on(LineDiscountsRecorded event) {
        discounts.add(event);
    }

    @ApplicationModuleListener
    void on(com.xavier.backend.orders.model.event.DeliveryDispatched event) {
        dispatches.add(event);
    }

    /** The dispatch notices of the order that arrived, waiting up to 10 s for {@code expected}. */
    public List<com.xavier.backend.orders.model.event.DeliveryDispatched> dispatchesOf(UUID orderId, int expected)
            throws InterruptedException {
        return await(() -> dispatches.stream().filter(event -> event.orderId().equals(orderId)).toList(), expected);
    }

    /** The discount notices of the order that arrived, waiting up to 10 s for {@code expected}. */
    public List<LineDiscountsRecorded> discountsOf(UUID orderId, int expected) throws InterruptedException {
        return await(() -> discounts.stream().filter(event -> event.orderId().equals(orderId)).toList(), expected);
    }

    /** The voids of the order that arrived, waiting up to 10 s for {@code expected}. */
    public List<OrderLinesVoided> voidsOf(UUID orderId, int expected) throws InterruptedException {
        return await(() -> voids.stream().filter(event -> event.orderId().equals(orderId)).toList(), expected);
    }

    /** The subtotal requests of the order that arrived, waiting up to 10 s for {@code expected}. */
    public List<SubtotalRequested> subtotalsOf(UUID orderId, int expected) throws InterruptedException {
        return await(() -> subtotals.stream().filter(event -> event.orderId().equals(orderId)).toList(), expected);
    }

    private static <T> List<T> await(java.util.function.Supplier<List<T>> read, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        List<T> found = read.get();
        while (found.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(50);
            found = read.get();
        }
        return found;
    }

    @EventListener
    void failInsideTheTransaction(OrderBatchConfirmed event) {
        if (failing.contains(event.orderId())) {
            throw new IllegalStateException("injected failure after publishing " + event.batchId());
        }
    }

    public void failRoundsOf(UUID orderId) {
        failing.add(orderId);
    }

    /** What arrived for the order, waiting up to 10 s for {@code expected} events (delivery is after commit). */
    public List<OrderBatchConfirmed> receivedFor(UUID orderId, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        List<OrderBatchConfirmed> mine = mine(orderId);
        while (mine.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(50);
            mine = mine(orderId);
        }
        return mine;
    }

    private List<OrderBatchConfirmed> mine(UUID orderId) {
        return received.stream().filter(event -> event.orderId().equals(orderId))
                .sorted(java.util.Comparator.comparingInt(OrderBatchConfirmed::batchNo)).toList();
    }
}
