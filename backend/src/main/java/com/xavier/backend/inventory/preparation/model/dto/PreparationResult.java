package com.xavier.backend.inventory.preparation.model.dto;

import com.xavier.backend.inventory.model.dto.MovementView;
import java.util.List;

/** A preparation after a command, with the movements its confirmation posted (none for a draft or a cancel). */
public record PreparationResult(PreparationView preparation, List<MovementView> movements) {
}
