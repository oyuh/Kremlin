package me.lawsonhart.kremlin.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatAdminTest {

    /** /slowchat takes a raw player-typed argument, so every rejection path matters. */
    @Test
    void slowChatParsesSecondsAndRefusesNonsense() {
        assertEquals(0L, ChatAdmin.parseSeconds("off"), "off means off");
        assertEquals(0L, ChatAdmin.parseSeconds("OFF"));
        assertEquals(0L, ChatAdmin.parseSeconds("none"));
        assertEquals(0L, ChatAdmin.parseSeconds("0"), "zero is also off");
        assertEquals(5L, ChatAdmin.parseSeconds("5"));
        assertEquals(3600L, ChatAdmin.parseSeconds("3600"), "the ceiling itself is allowed");

        assertEquals(-1L, ChatAdmin.parseSeconds("3601"), "past the ceiling is a typo, not a plan");
        assertEquals(-1L, ChatAdmin.parseSeconds("-5"), "negative would never let anyone speak");
        assertEquals(-1L, ChatAdmin.parseSeconds("fast"));
        assertEquals(-1L, ChatAdmin.parseSeconds(""));
        assertEquals(-1L, ChatAdmin.parseSeconds("5s"));
    }
}
