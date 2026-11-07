package com.xavier.backend.promotions.model;

import com.xavier.backend.promotions.model.dto.PromotionSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A happy-hour window of a promotion, in the store's zone (D103). */
@Embeddable
public class TimeWindow {

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "smallint[]")
    private Short[] daysOfWeek;

    @Column(nullable = false)
    private LocalTime localStart;

    @Column(nullable = false)
    private LocalTime localEnd;

    protected TimeWindow() {
        // JPA
    }

    TimeWindow(List<Integer> daysOfWeek, LocalTime start, LocalTime end) {
        this.daysOfWeek = daysOfWeek.stream().sorted().distinct().map(Integer::shortValue).toArray(Short[]::new);
        this.localStart = start;
        this.localEnd = end;
    }

    public PromotionSnapshot.Window toWindow() {
        return new PromotionSnapshot.Window(Arrays.stream(daysOfWeek).map(Short::intValue).toList(), localStart,
                localEnd);
    }
}
