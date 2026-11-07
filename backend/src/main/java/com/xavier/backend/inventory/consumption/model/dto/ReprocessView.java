package com.xavier.backend.inventory.consumption.model.dto;

import com.xavier.backend.inventory.model.dto.MovementView;
import java.util.List;

/** A reprocessed exception, resolved, and the movements it posted (none when its line is not tracked any more). */
public record ReprocessView(ExceptionView exception, List<MovementView> movements) {
}
