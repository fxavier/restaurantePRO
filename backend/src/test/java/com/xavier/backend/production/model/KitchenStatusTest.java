package com.xavier.backend.production.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A round on several stations is as far along as its least advanced station. */
class KitchenStatusTest {

    @Test
    void aRoundIsAsFarAlongAsItsLeastAdvancedStation() {
        assertThat(KitchenStatus.leastAdvanced(List.of(KitchenStatus.READY, KitchenStatus.QUEUED)))
                .isEqualTo(KitchenStatus.QUEUED);
        assertThat(KitchenStatus.leastAdvanced(List.of(KitchenStatus.BUMPED, KitchenStatus.IN_PREPARATION)))
                .isEqualTo(KitchenStatus.IN_PREPARATION);
    }

    @Test
    void aRoundReadyOnOneStationAndServedOnAnotherIsReady() {
        assertThat(KitchenStatus.leastAdvanced(List.of(KitchenStatus.BUMPED, KitchenStatus.READY)))
                .isEqualTo(KitchenStatus.READY);
    }
}
