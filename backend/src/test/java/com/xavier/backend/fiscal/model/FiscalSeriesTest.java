package com.xavier.backend.fiscal.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.fiscal.exception.IllegalSeriesStateException;
import com.xavier.backend.fiscal.exception.InvalidSeriesException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalSeriesTest {

    static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void aNewSeriesIsActiveAndNumbersFromItsFirstNumber() {
        FiscalSeries series = FiscalSeries.create(UUID.randomUUID(), DocumentType.SALE, " A ", 5, NOW);

        assertThat(series.isActive()).isTrue();
        assertThat(series.getCode()).isEqualTo("A");
        assertThat(series.getNextNumber()).isEqualTo(5);
        assertThat(series.takeNextNumber(NOW)).isEqualTo(5);
        assertThat(series.takeNextNumber(NOW)).isEqualTo(6);
        assertThat(series.getNextNumber()).isEqualTo(7);
    }

    @Test
    void theFirstNumberIsAtLeastOne() {
        assertThatThrownBy(() -> FiscalSeries.create(UUID.randomUUID(), DocumentType.SALE, "A", 0, NOW))
                .isInstanceOf(InvalidSeriesException.class);
    }

    @Test
    void aSeriesNeedsACode() {
        assertThatThrownBy(() -> FiscalSeries.create(UUID.randomUUID(), DocumentType.SALE, " ", 1, NOW))
                .isInstanceOf(InvalidSeriesException.class);
    }

    @Test
    void anInactiveSeriesGivesNoNumberAndCannotBeDeactivatedAgain() {
        FiscalSeries series = FiscalSeries.create(UUID.randomUUID(), DocumentType.SALE, "A", 1, NOW);
        series.deactivate(NOW);

        assertThat(series.isActive()).isFalse();
        assertThatThrownBy(() -> series.takeNextNumber(NOW)).isInstanceOf(IllegalSeriesStateException.class);
        assertThatThrownBy(() -> series.deactivate(NOW)).isInstanceOf(IllegalSeriesStateException.class);
    }
}
