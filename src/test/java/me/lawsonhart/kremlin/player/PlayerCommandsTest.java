package me.lawsonhart.kremlin.player;

import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerCommandsTest {

    /**
     * setWalkSpeed and setFlySpeed throw outside 0-1, and the argument is player-typed, so every
     * path out of this has to land inside that range whatever was asked for.
     */
    @Test
    void speedIsClampedIntoTheRangeTheApiAccepts() {
        assertEquals(0.5, PlayerCommands.speed(5, 1.0, false), 1e-9, "5 of 10 is half speed");
        assertEquals(0.1, PlayerCommands.speed(1, 1.0, false), 1e-9);
        assertEquals(0.0, PlayerCommands.speed(0, 1.0, false), 1e-9);

        assertEquals(1.0, PlayerCommands.speed(999, 1.0, true), 1e-9, "over the top clamps to 1");
        assertEquals(0.0, PlayerCommands.speed(-999, 1.0, true), 1e-9, "under the bottom clamps to 0");
    }

    /** max-walk-speed / max-fly-speed, which the server config sets to 0.8. */
    @Test
    void theConfiguredCapHoldsUnlessBypassed() {
        assertEquals(0.8, PlayerCommands.speed(10, 0.8, false), 1e-9, "/speed 10 really means 8");
        assertEquals(0.5, PlayerCommands.speed(5, 0.8, false), 1e-9, "under the cap is untouched");
        assertEquals(1.0, PlayerCommands.speed(10, 0.8, true), 1e-9, "the bypass node ignores it");
    }

    /** A nonsense cap in the config must not become a nonsense argument to the API. */
    @Test
    void aBrokenCapCannotProduceAnOutOfRangeSpeed() {
        assertEquals(1.0, PlayerCommands.speed(10, 99.0, false), 1e-9);
        assertEquals(0.0, PlayerCommands.speed(10, -5.0, false), 1e-9);
    }

    @Test
    void gameModesAreReachableByEveryFormPlayersType() {
        assertEquals(GameMode.SURVIVAL, PlayerCommands.mode("survival"));
        assertEquals(GameMode.SURVIVAL, PlayerCommands.mode("s"));
        assertEquals(GameMode.SURVIVAL, PlayerCommands.mode("0"));
        assertEquals(GameMode.CREATIVE, PlayerCommands.mode("CREATIVE"), "case insensitive");
        assertEquals(GameMode.CREATIVE, PlayerCommands.mode("1"));
        assertEquals(GameMode.ADVENTURE, PlayerCommands.mode("a"));
        assertEquals(GameMode.SPECTATOR, PlayerCommands.mode("sp"));
        assertEquals(GameMode.SPECTATOR, PlayerCommands.mode("3"));

        assertNull(PlayerCommands.mode("4"), "out of range is not a mode");
        assertNull(PlayerCommands.mode("survivor"));
        assertNull(PlayerCommands.mode(""));
    }
}
