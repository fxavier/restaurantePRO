package com.xavier.backend.production.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.xavier.backend.production.adapter.PrinterTransport;
import org.junit.jupiter.api.Test;

/**
 * The test stand-in for every store's bridge claims nothing, in any cached context of the JVM, while a suite that is
 * the bridge itself runs ({@link PausedPrintStandIns}): its jobs are its own to claim.
 */
class InProcessPrintBridgePauseTest {

    @Test
    void whilePausedNoStandInClaimsAndAfterwardsItClaimsAgain() throws Exception {
        PrintQueue queue = mock(PrintQueue.class);
        InProcessPrintBridge bridge = new InProcessPrintBridge(queue, mock(PrinterTransport.class), null, 1);
        try {
            try (AutoCloseable paused = InProcessPrintBridge.pauseEverywhere()) {
                assertThat(bridge.poll()).isZero();
                verify(queue, never()).claimDue(anyInt());
            }

            bridge.poll();
            verify(queue).claimDue(1);
        } finally {
            bridge.destroy();
        }
    }
}
