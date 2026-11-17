package com.xavier.bridge;

import java.util.UUID;

/**
 * A job the backend leased to this bridge (D187): the printer's LAN address and the ESC/POS bytes, already rendered for
 * the printer's profile by the backend (D154–D158), and whether to ask the printer's status first (D157).
 */
record ClaimedJob(UUID id, String host, int port, byte[] payload, boolean probeStatus) {
}
