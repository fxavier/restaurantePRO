package com.xavier.backend.production.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.production.model.CutMode;
import com.xavier.backend.production.model.PrinterProfile;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * D158, ADR 0038: a drawer kick is {@code ESC p m t1 t2} and nothing else: {@code m} the profile's pin (0 = connector
 * pin 2, 1 = pin 5), {@code t1} the pulse in 2 ms units, {@code t2} a fixed 500 ms off time ({@code FA}).
 */
class DrawerKickBytesTest {

    @Test
    void pinTwoWithTheDefaultPulse() {
        assertThat(hex(DrawerKickService.payload(drawer(0, 100)))).isEqualTo("1b700032fa");
    }

    @Test
    void pinFiveWithTheLongestPulse() {
        assertThat(hex(DrawerKickService.payload(drawer(1, 510)))).isEqualTo("1b7001fffa");
    }

    @Test
    void theShortestPulseIsOneUnit() {
        assertThat(hex(DrawerKickService.payload(drawer(0, 2)))).isEqualTo("1b700001fa");
    }

    @Test
    void aPrinterWithoutADrawerHasNoKick() {
        assertThatThrownBy(() -> DrawerKickService.payload(PrinterProfile.DEFAULT))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PrinterProfile drawer(int pin, int pulseMs) {
        return new PrinterProfile(58, 32, null, CutMode.NONE, 4, false, pin, pulseMs);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
