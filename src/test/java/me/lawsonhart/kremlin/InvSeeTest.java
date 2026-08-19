package me.lawsonhart.kremlin;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chest-slot to inventory-slot map. Getting this wrong doesn't throw -- it silently writes
 * someone's helmet into their hotbar, or drops a slot on the floor when the menu is saved back,
 * so the property worth pinning is that the map is a bijection over all 41 player slots.
 */
class InvSeeTest {

    @Test
    void everyPlayerSlotIsReachableExactlyOnce() {
        Set<Integer> seen = new HashSet<>();
        for (int slot = 0; slot < InvSee.GUI_SIZE; slot++) {
            int index = InvSee.GUI[slot];
            if (index < 0) continue;
            assertTrue(index < InvSee.SLOTS, "chest slot " + slot + " maps outside the inventory");
            assertTrue(seen.add(index),
                    "inventory slot " + index + " is shown twice -- one copy would overwrite the other");
        }
        assertEquals(InvSee.SLOTS, seen.size(), "every one of the 41 player slots must be shown");
    }

    @Test
    void armourAndOffHandLandWhereThePlayerScreenPutsThem() {
        assertEquals(39, InvSee.GUI[36], "helmet");
        assertEquals(38, InvSee.GUI[37], "chestplate");
        assertEquals(37, InvSee.GUI[38], "leggings");
        assertEquals(36, InvSee.GUI[39], "boots");
        assertEquals(40, InvSee.GUI[40], "off-hand");
        assertEquals(0, InvSee.GUI[27], "the hotbar sits under the main inventory, as on the player screen");
        assertEquals(9, InvSee.GUI[0], "the main inventory starts at the top");
    }

    /**
     * The whole override rests on the typed label reducing to something we claim. Essentials owns
     * /ec and /enderchest and registers /eec and /eenderchest as its own way back in, so missing
     * any one of them leaves a door open to its version -- which can't see offline players.
     */
    @Test
    void everyWayToReachEssentialsEnderChestLandsOnOurs() {
        for (String typed : new String[]{
                "/enderchest", "/ec", "/EC Bob", "/echest Bob", "/enderc",
                "/eec", "/eechest", "/eenderchest", "/eenderc",
                "/essentials:ec", "/Essentials:EnderChest Bob", "/essentialsx:eec"}) {
            assertTrue(InvSee.ENDER.contains(Combat.rootCommand(typed)),
                    typed + " would fall through to Essentials");
        }
        assertTrue(InvSee.INVSEE.contains(Combat.rootCommand("/essentials:InvSee Bob")));
        assertTrue(InvSee.INVSEE.contains(Combat.rootCommand("/einvsee Bob")));

        assertFalse(InvSee.ENDER.contains(Combat.rootCommand("/echo hi")), "no grabbing unrelated labels");
    }

    /** The bits of the info paper that are arithmetic rather than API calls. */
    @Test
    void statusReadsTheWayAPlayerWouldWriteIt() {
        assertEquals("20", InvSee.num(20.0), "full health has no trailing zero");
        assertEquals("18.5", InvSee.num(18.5));
        assertEquals("0", InvSee.num(0.0));

        assertEquals("1:23", InvSee.clock(83));
        assertEquals("0:07", InvSee.clock(7), "seconds are padded so the column lines up");
        assertEquals("0:00", InvSee.clock(-5), "an effect that just expired is not negative time");

        assertEquals("I", InvSee.roman(1));
        assertEquals("IV", InvSee.roman(4));
        assertEquals("42", InvSee.roman(42), "past the table, the number itself will do");
    }

    @Test
    void theHubNameIsReservedAlongsideBedAndHq() {
        assertTrue(Homes.reserved("hub"));
        assertTrue(Homes.reserved("HUB"), "a home called HUB would shadow the community hub button");
        assertTrue(Homes.reserved(Homes.BED));
        assertTrue(Homes.reserved(Homes.HQ));
        assertFalse(Homes.reserved("base"));
    }
}
