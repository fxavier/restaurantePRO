package com.xavier.backend.production.adapter;

import com.xavier.backend.production.model.PrinterAddress;

/**
 * The printer port (D57). An implementation talks to hardware, so it is an external call (D48): it starts with
 * {@code ExternalCalls.requireNoTransaction()}, and it never throws for a printer problem, it reports it.
 */
public interface PrinterTransport {

    /**
     * @param probeStatus ask the printer's status first (D157) and send nothing when it is not ready
     */
    SendResult send(PrinterAddress address, byte[] payload, boolean probeStatus);
}
