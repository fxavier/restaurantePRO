package com.xavier.backend.fiscal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.organization.BusinessDays;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The backfill window of an R2 document must agree with {@link BusinessDays#of}: the computed start of a business
 * day belongs to that day and the microsecond before it to the previous one, also across DST gaps and overlaps.
 */
class BusinessDayStartTest {

    @ParameterizedTest
    @CsvSource({
            "Africa/Maputo, 05:00, 2026-11-04",
            "Europe/Lisbon, 01:30, 2026-03-29",   // the cutoff falls in the spring-forward gap
            "Europe/Lisbon, 01:30, 2026-10-25",   // the cutoff is repeated by the fall-back overlap
            "America/Sao_Paulo, 00:00, 2026-01-01",
            "Europe/Lisbon, 05:00, 2026-03-29"
    })
    void theStartOfABusinessDayIsItsFirstInstant(String zone, String cutoff, String day) {
        ZoneId z = ZoneId.of(zone);
        LocalTime c = LocalTime.parse(cutoff);
        LocalDate d = LocalDate.parse(day);

        Instant start = DocumentFactsService.startOfBusinessDay(d, z, c);

        assertThat(BusinessDays.of(start, z, c)).isEqualTo(d);
        assertThat(BusinessDays.of(start.minusNanos(1_000), z, c)).isEqualTo(d.minusDays(1));
    }
}
