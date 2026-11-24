package com.xavier.backend.production.model;

import static com.xavier.backend.production.model.KitchenStatus.BUMPED;
import static com.xavier.backend.production.model.KitchenStatus.IN_PREPARATION;
import static com.xavier.backend.production.model.KitchenStatus.QUEUED;
import static com.xavier.backend.production.model.KitchenStatus.READY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A round on several stations, as the POS order panel shows it: SERVED when every station has bumped it, READY when
 * every station has it ready or bumped (but not all bumped), IN_PREPARATION once any station has started it, else
 * QUEUED.
 */
class KitchenStatusTest {

    @Test
    void aRoundIsServedWhenEveryStationHasBumpedIt() {
        assertThat(RoundKitchenState.of(List.of(BUMPED, BUMPED))).isEqualTo(RoundKitchenState.SERVED);
        assertThat(RoundKitchenState.of(List.of(BUMPED))).isEqualTo(RoundKitchenState.SERVED);
    }

    @Test
    void aRoundIsReadyWhenEveryStationHasItReadyOrBumpedButNotAllBumped() {
        assertThat(RoundKitchenState.of(List.of(READY, BUMPED))).isEqualTo(RoundKitchenState.READY);
        assertThat(RoundKitchenState.of(List.of(READY, READY))).isEqualTo(RoundKitchenState.READY);
    }

    @Test
    void aRoundIsInPreparationOnceAnyStationHasStartedIt() {
        assertThat(RoundKitchenState.of(List.of(IN_PREPARATION, QUEUED))).isEqualTo(RoundKitchenState.IN_PREPARATION);
        assertThat(RoundKitchenState.of(List.of(READY, QUEUED))).isEqualTo(RoundKitchenState.IN_PREPARATION);
        assertThat(RoundKitchenState.of(List.of(BUMPED, QUEUED))).isEqualTo(RoundKitchenState.IN_PREPARATION);
    }

    @Test
    void aRoundNoStationHasStartedIsQueued() {
        assertThat(RoundKitchenState.of(List.of(QUEUED, QUEUED))).isEqualTo(RoundKitchenState.QUEUED);
    }
}
