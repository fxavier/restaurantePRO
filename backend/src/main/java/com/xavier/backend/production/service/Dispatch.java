package com.xavier.backend.production.service;

import com.xavier.backend.production.model.PrinterAddress;
import java.util.UUID;

/**
 * A claimed job, rendered for its printer and ready to send with no transaction: everything the send needs (whether
 * to probe the printer's status first, D157), nothing it locks.
 */
record Dispatch(UUID jobId, UUID printerId, PrinterAddress address, byte[] payload, boolean probeStatus) {
}
