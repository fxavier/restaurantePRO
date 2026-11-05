package com.xavier.backend.production.service;

import com.xavier.backend.production.model.PrinterAddress;
import java.util.UUID;

/** A claimed job, rendered and ready to send with no transaction: everything the send needs, nothing it locks. */
record Dispatch(UUID jobId, UUID printerId, PrinterAddress address, byte[] payload) {
}
