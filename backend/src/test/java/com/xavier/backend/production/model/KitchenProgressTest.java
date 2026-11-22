package com.xavier.backend.production.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.production.exception.ProductionConflictException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A round's progress on the kitchen display (ADR 0044): QUEUED → IN_PREPARATION → READY → BUMPED; a recall steps
 * back once (READY → IN_PREPARATION, BUMPED → READY). Every other command is refused, and each transition keeps
 * when it happened and who did it.
 */
class KitchenProgressTest {

    static final Instant T0 = Instant.parse("2026-11-21T19:00:00Z");
    static final UUID TICKET = UUID.randomUUID();
    static final UUID STORE = UUID.randomUUID();
    static final UUID COOK = UUID.randomUUID();
    static final UUID RUNNER = UUID.randomUUID();

    @Test
    void aNewRoundIsQueuedOnItsStation() {
        KitchenProgress progress = queued();

        assertThat(progress.getStatus()).isEqualTo(KitchenStatus.QUEUED);
        assertThat(progress.getDestination()).isEqualTo("GRILL");
        assertThat(progress.toView().queuedAt()).isEqualTo(T0);
        assertThat(progress.toView().started()).isNull();
    }

    @Test
    void startingPutsItInPreparationWithTheCookAndTheTime() {
        KitchenProgress progress = queued();

        progress.start(COOK, T0.plusSeconds(60));

        assertThat(progress.getStatus()).isEqualTo(KitchenStatus.IN_PREPARATION);
        assertThat(progress.toView().started().at()).isEqualTo(T0.plusSeconds(60));
        assertThat(progress.toView().started().by()).isEqualTo(COOK);
    }

    @Test
    void markingReadyRecordsWhoFinishedItAndWhen() {
        KitchenProgress progress = inPreparation();

        progress.ready(COOK, T0.plusSeconds(600));

        assertThat(progress.getStatus()).isEqualTo(KitchenStatus.READY);
        assertThat(progress.toView().ready().at()).isEqualTo(T0.plusSeconds(600));
        assertThat(progress.toView().ready().by()).isEqualTo(COOK);
    }

    @Test
    void bumpingTakesAReadyRoundOffTheScreen() {
        KitchenProgress progress = ready();

        progress.bump(RUNNER, T0.plusSeconds(700));

        assertThat(progress.getStatus()).isEqualTo(KitchenStatus.BUMPED);
        assertThat(progress.toView().bumped().at()).isEqualTo(T0.plusSeconds(700));
        assertThat(progress.toView().bumped().by()).isEqualTo(RUNNER);
    }

    @Test
    void recallingAReadyRoundSendsItBackToPreparation() {
        KitchenProgress progress = ready();

        progress.recall(COOK, T0.plusSeconds(650));

        assertThat(progress.getStatus()).isEqualTo(KitchenStatus.IN_PREPARATION);
        assertThat(progress.toView().ready()).isNull();
        assertThat(progress.toView().started().by()).isEqualTo(COOK);
        assertThat(progress.toView().recalled().at()).isEqualTo(T0.plusSeconds(650));
        assertThat(progress.toView().recalled().by()).isEqualTo(COOK);
    }

    @Test
    void recallingABumpedRoundPutsItBackOnTheScreenAsReady() {
        KitchenProgress progress = ready();
        progress.bump(RUNNER, T0.plusSeconds(700));

        progress.recall(RUNNER, T0.plusSeconds(720));

        assertThat(progress.getStatus()).isEqualTo(KitchenStatus.READY);
        assertThat(progress.toView().bumped()).isNull();
        assertThat(progress.toView().ready().at()).isEqualTo(T0.plusSeconds(600));
        assertThat(progress.toView().recalled().by()).isEqualTo(RUNNER);
    }

    @Test
    void aQueuedRoundCanOnlyBeStarted() {
        refused(queued(), progress -> progress.ready(COOK, T0), "ready");
        refused(queued(), progress -> progress.bump(COOK, T0), "bump");
        refused(queued(), progress -> progress.recall(COOK, T0), "recall");
    }

    @Test
    void aRoundInPreparationCanOnlyBeMarkedReady() {
        refused(inPreparation(), progress -> progress.start(COOK, T0), "start");
        refused(inPreparation(), progress -> progress.bump(COOK, T0), "bump");
        refused(inPreparation(), progress -> progress.recall(COOK, T0), "recall");
    }

    @Test
    void aReadyRoundCanOnlyBeBumpedOrRecalled() {
        refused(ready(), progress -> progress.start(COOK, T0), "start");
        refused(ready(), progress -> progress.ready(COOK, T0), "ready");
    }

    @Test
    void aBumpedRoundCanOnlyBeRecalled() {
        KitchenProgress bumped = ready();
        bumped.bump(RUNNER, T0.plusSeconds(700));

        refused(bumped, progress -> progress.start(COOK, T0), "start");
        refused(bumped, progress -> progress.ready(COOK, T0), "ready");
        refused(bumped, progress -> progress.bump(COOK, T0), "bump");
    }

    private static void refused(KitchenProgress progress, Consumer<KitchenProgress> command, String name) {
        KitchenStatus before = progress.getStatus();

        assertThatThrownBy(() -> command.accept(progress)).isInstanceOf(ProductionConflictException.class)
                .hasMessageContaining(name).hasMessageContaining(before.name())
                .satisfies(error -> assertThat(((ProductionConflictException) error).code())
                        .isEqualTo("ILLEGAL_KITCHEN_STATE"));
        assertThat(progress.getStatus()).isEqualTo(before);
    }

    private static KitchenProgress queued() {
        return KitchenProgress.queue(TICKET, STORE, "GRILL", T0);
    }

    private static KitchenProgress inPreparation() {
        KitchenProgress progress = queued();
        progress.start(COOK, T0.plusSeconds(60));
        return progress;
    }

    private static KitchenProgress ready() {
        KitchenProgress progress = inPreparation();
        progress.ready(COOK, T0.plusSeconds(600));
        return progress;
    }
}
