package com.xavier.backend.production.model.dto;

import jakarta.validation.constraints.Size;

/** {@code POST /pos/drawer-kicks}: why the drawer opens (D158); a blank one is a 422 {@code REASON_REQUIRED}. */
public record DrawerKickRequest(@Size(max = 500) String reason) {
}
