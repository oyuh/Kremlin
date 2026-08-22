package me.lawsonhart.kremlin.teleport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two gates the new toggles hang off. Both decide whether somebody gets moved without
 * being asked, which is exactly the kind of thing that should not quietly flip.
 */
class TeleportOptionsTest {

    @Test
    void autoAcceptNeverAppliesToARequestThatWouldMoveYou() {
        assertTrue(Tpa.autoAccepts(false, true), "/tpa with auto on: they come to you, no prompt");
        assertFalse(Tpa.autoAccepts(true, true), "/tpahere would move YOU -- still asks");
        assertFalse(Tpa.autoAccepts(false, false), "auto off: the normal prompt");
        assertFalse(Tpa.autoAccepts(true, false));
    }

    @Test
    void onlyTheDestinationOfATeleportMayBeOffline() {
        assertTrue(TpCommands.offlineDestination(false, 1), "/tp Bob -> where Bob logged out");
        assertFalse(TpCommands.offlineDestination(true, 1), "/tphere Bob has nobody to bring");
        assertFalse(TpCommands.offlineDestination(false, 2), "/tp Bob Steve cannot move an offline Bob");
    }
}
