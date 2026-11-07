package com.xavier.backend.orders.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.orders.DeliveryStatus;
import com.xavier.backend.orders.delivery.exception.DeliveryConflictException;
import com.xavier.backend.orders.delivery.exception.DeliveryRuleException;
import com.xavier.backend.orders.delivery.model.Delivery;
import com.xavier.backend.orders.delivery.model.DeliveryAddress;
import com.xavier.backend.orders.delivery.model.DeliveryZone;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * D127, every state × every command: dispatch from PENDING or RETURNED (a retry) with a courier, delivered and
 * returned from OUT_FOR_DELIVERY, cancel from PENDING or RETURNED (else 422 {@code DELIVERY_IN_PROGRESS}); any other
 * transition is a 409 {@code DELIVERY_STATE}. The address is editable only while PENDING (422 {@code ADDRESS_FROZEN}).
 */
class DeliveryLifecycleTest {

    static final Instant NOW = Instant.parse("2027-03-01T12:00:00Z");
    static final UUID COURIER = UUID.randomUUID();
    static final UUID STORE = UUID.randomUUID();
    static final DeliveryZone ZONE = DeliveryZone.create(STORE, "CENTRO", "Centro", UUID.randomUUID(), null);
    static final DeliveryAddress ADDRESS = new DeliveryAddress("Rua 1", null, "Maputo", null, "2nd floor");

    enum Command {
        DISPATCH(d -> d.dispatch(COURIER, NOW)),
        DELIVERED(d -> d.markDelivered(NOW)),
        RETURNED(Delivery::markReturned),
        CANCEL(Delivery::cancel);

        final Consumer<Delivery> apply;

        Command(Consumer<Delivery> apply) {
            this.apply = apply;
        }
    }

    static final Map<Command, Set<DeliveryStatus>> ALLOWED_FROM = Map.of(
            Command.DISPATCH, EnumSet.of(DeliveryStatus.PENDING, DeliveryStatus.RETURNED),
            Command.DELIVERED, EnumSet.of(DeliveryStatus.OUT_FOR_DELIVERY),
            Command.RETURNED, EnumSet.of(DeliveryStatus.OUT_FOR_DELIVERY),
            Command.CANCEL, EnumSet.of(DeliveryStatus.PENDING, DeliveryStatus.RETURNED));

    static final Map<Command, DeliveryStatus> TARGET = Map.of(
            Command.DISPATCH, DeliveryStatus.OUT_FOR_DELIVERY,
            Command.DELIVERED, DeliveryStatus.DELIVERED,
            Command.RETURNED, DeliveryStatus.RETURNED,
            Command.CANCEL, DeliveryStatus.CANCELLED);

    @ParameterizedTest
    @EnumSource(DeliveryStatus.class)
    void everyCommandFromEveryState(DeliveryStatus from) {
        for (Command command : Command.values()) {
            Delivery delivery = in(from);
            if (ALLOWED_FROM.get(command).contains(from)) {
                command.apply.accept(delivery);
                assertThat(delivery.getStatus()).as(command + " from " + from).isEqualTo(TARGET.get(command));
            } else if (command == Command.CANCEL) {
                assertThatThrownBy(() -> command.apply.accept(delivery)).as(command + " from " + from)
                        .isInstanceOf(DeliveryRuleException.class)
                        .hasFieldOrPropertyWithValue("code", "DELIVERY_IN_PROGRESS");
                assertThat(delivery.getStatus()).isEqualTo(from);
            } else {
                assertThatThrownBy(() -> command.apply.accept(delivery)).as(command + " from " + from)
                        .isInstanceOf(DeliveryConflictException.class)
                        .hasFieldOrPropertyWithValue("code", "DELIVERY_STATE");
                assertThat(delivery.getStatus()).isEqualTo(from);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DeliveryStatus.class)
    void theAddressIsEditableOnlyWhilePending(DeliveryStatus status) {
        Delivery delivery = in(status);
        DeliveryAddress moved = new DeliveryAddress("Av. 2", "B", "Matola", "1100", null);

        if (status == DeliveryStatus.PENDING) {
            delivery.edit(moved, null, NOW.plusSeconds(3600));
            assertThat(delivery.address()).isEqualTo(moved);
            assertThat(delivery.getPromisedAt()).isEqualTo(NOW.plusSeconds(3600));
        } else {
            assertThatThrownBy(() -> delivery.edit(moved, null, null)).isInstanceOf(DeliveryRuleException.class)
                    .hasFieldOrPropertyWithValue("code", "ADDRESS_FROZEN");
            assertThat(delivery.address()).isEqualTo(ADDRESS);
        }
    }

    @org.junit.jupiter.api.Test
    void aDispatchRecordsTheCourierAndADeliveryWhenItArrived() {
        Delivery delivery = in(DeliveryStatus.PENDING);

        delivery.dispatch(COURIER, NOW);
        delivery.markDelivered(NOW.plusSeconds(600));

        assertThat(delivery.getCourierEmployeeId()).isEqualTo(COURIER);
        assertThat(delivery.getDispatchedAt()).isEqualTo(NOW);
        assertThat(delivery.getDeliveredAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(delivery.summary().zoneCode()).isEqualTo("CENTRO");
    }

    static Delivery in(DeliveryStatus status) {
        Delivery delivery = Delivery.open(UUID.randomUUID(), STORE, UUID.randomUUID(), "841234567", ADDRESS, ZONE,
                null);
        switch (status) {
            case PENDING -> { }
            case OUT_FOR_DELIVERY -> delivery.dispatch(COURIER, NOW);
            case DELIVERED -> {
                delivery.dispatch(COURIER, NOW);
                delivery.markDelivered(NOW);
            }
            case RETURNED -> {
                delivery.dispatch(COURIER, NOW);
                delivery.markReturned();
            }
            case CANCELLED -> delivery.cancel();
        }
        return delivery;
    }
}
