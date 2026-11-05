package com.xavier.backend.dining.model.dto;

import java.util.UUID;

/** A table and the area it is in now, for labels (reports, exports): codes and names, no state. */
public record TableInfo(UUID tableId, String tableCode, UUID areaId, String areaCode, String areaName) {
}
