package com.xavier.backend.production.model.dto;

import java.util.UUID;

/**
 * A job a print bridge claimed (D187): where to send it on the store's LAN and the ESC/POS bytes to send (rendered by
 * the backend for the printer's profile, D154–D158, base64), and whether to ask the printer's status first (D157).
 */
public record BridgeJobView(UUID id, String host, int port, String payload, boolean probeStatus) {
}
