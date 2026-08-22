package me.lawsonhart.kremlin.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The only branch in the presence string is the plural, and an empty server is the common case. */
class BotPresenceTest {

    @Test
    void pluralises() {
        assertEquals("0 players online", Bot.presence(0));
        assertEquals("1 player online", Bot.presence(1));
        assertEquals("2 players online", Bot.presence(2));
    }
}
