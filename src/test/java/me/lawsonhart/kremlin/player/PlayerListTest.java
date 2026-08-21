package me.lawsonhart.kremlin.player;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlayerListTest {

    @Test
    void lastSeenReadsAsTheLargestUnitThatFits() {
        long now = System.currentTimeMillis();
        assertEquals("never", PlayerList.ago(0L), "a player we have no timestamp for");
        assertEquals("just now", PlayerList.ago(now));
        assertEquals("5m ago", PlayerList.ago(now - TimeUnit.MINUTES.toMillis(5)));
        assertEquals("3h ago", PlayerList.ago(now - TimeUnit.HOURS.toMillis(3)));
        assertEquals("2d ago", PlayerList.ago(now - TimeUnit.DAYS.toMillis(2)));
        assertEquals("just now", PlayerList.ago(now + TimeUnit.DAYS.toMillis(1)),
                "a clock that ran backwards must not print a negative age");
    }

    /**
     * The crash this replaced: a nickname is text the player chose, and MiniMessage throws on any
     * string containing legacy colour codes -- which is exactly the form EssentialsX stores a
     * nickname in. It is deserialised as legacy and appended as a component instead, so a hex
     * gradient renders in its own colours and a MiniMessage tag stays literal text.
     */
    @Test
    void essentialsNicknamesRenderInsteadOfBlowingUpTheMenu() {
        // Byte for byte what Essentials had written for the nick that crashed /playerlist.
        String stored = "§x§2§7§4§5§C§8Da"
                + "§x§1§4§7§2§E§4l"
                + "§x§0§0§9§F§F§Fton";
        assertEquals("Dalton", plain(PlayerList.legacy(stored)));
        assertEquals("Bob", plain(PlayerList.legacy("&cBob")), "the ampersand form too");
        assertEquals("<red>x", plain(PlayerList.legacy("<red>x")), "a tag is literal text here, not markup");
        assertEquals("", plain(PlayerList.legacy("")));
    }

    private static String plain(net.kyori.adventure.text.Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }
}
