package com.footballmanagergamesimulator.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferWindowTest {

    @Test
    @DisplayName("""
            GIVEN the season calendar running from 1 August (day 1) to 31 July (day 365)
            WHEN the transfer window is checked on the edge days
            THEN it is open 1 June – 30 September and in January, closed in October–December and February–May
            """)
    void windowIsJuneToSeptemberAndJanuary() {
        assertTrue(TransferWindow.isOpen(1), "1 August");
        assertTrue(TransferWindow.isOpen(61), "30 September");
        assertFalse(TransferWindow.isOpen(62), "1 October");
        assertFalse(TransferWindow.isOpen(153), "31 December");
        assertTrue(TransferWindow.isOpen(154), "1 January");
        assertTrue(TransferWindow.isOpen(184), "31 January");
        assertFalse(TransferWindow.isOpen(185), "1 February");
        assertFalse(TransferWindow.isOpen(304), "31 May");
        assertTrue(TransferWindow.isOpen(305), "1 June");
        assertTrue(TransferWindow.isOpen(365), "31 July");
    }
}
