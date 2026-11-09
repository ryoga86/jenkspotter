package de.pdenis.jenkspotter.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LogConsoleManagerTest {
    @Test
    fun `hidden console notes and CRLF are removed`() {
        val raw = "Started by user \u001B[8mha:////4A0iiTZ2+AAAA==\u001B[0mDev User\r\n\u001B[8mha:////xyz\u001B[0m[Pipeline] Start\r\n"
        assertEquals("Started by user Dev User\n[Pipeline] Start\n", LogConsoleManager.stripConsoleNotes(raw))
    }

    @Test
    fun `ansi colours are kept for the decoder`() {
        val raw = "\u001B[31mred\u001B[0m\n"
        assertEquals(raw, LogConsoleManager.stripConsoleNotes(raw))
    }
}
