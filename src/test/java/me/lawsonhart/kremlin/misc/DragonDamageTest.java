package me.lawsonhart.kremlin.misc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DragonDamageTest {

    /** The egg goes to whoever tops this table, so the numbers under it have to be sane. */
    @Test
    void dragonSharesFormatCleanlyAndNeverDivideByZero() {
        assertEquals("128.5", DragonDamage.round(128.46));
        assertEquals("0.0", DragonDamage.round(0.0));
        assertEquals("200.0", DragonDamage.round(200.0), "a solo kill is the dragon full health bar");

        assertEquals("37.5%", DragonDamage.percent(75.0, 200.0));
        assertEquals("100.0%", DragonDamage.percent(200.0, 200.0), "one player did all of it");
        assertEquals("0%", DragonDamage.percent(5.0, 0.0), "nothing recorded must not blow up the table");
    }
}
