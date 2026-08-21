package me.lawsonhart.kremlin.teleport;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Warp naming, and the shape of the Essentials warp files the importer reads.
 *
 * Essentials writes one file per warp with the location spread across flat keys, not as a
 * serialised Location -- reading it the wrong way imports every warp at 0,0,0.
 */
class WarpsTest {

    /** Verbatim shape of a plugins/Essentials/warps/<name>.yml file. */
    private static final String ESSENTIALS_WARP = """
            name: Spawn
            lastowner: 069a79f4-44e9-4726-a5be-fca90e38aaf5
            world: world
            x: 128.5
            y: 64.0
            z: -256.5
            yaw: 90.0
            pitch: 12.5
            """;

    @Test
    void anEssentialsWarpFileIsReadFromFlatKeys() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader(ESSENTIALS_WARP));

        assertEquals("Spawn", y.getString("name"), "the display name, not the filename");
        assertEquals("world", y.getString("world"));
        assertEquals(128.5, y.getDouble("x"), 1e-9);
        assertEquals(64.0, y.getDouble("y"), 1e-9);
        assertEquals(-256.5, y.getDouble("z"), 1e-9);
        assertEquals(90.0f, (float) y.getDouble("yaw"), 1e-6f);
        assertEquals(12.5f, (float) y.getDouble("pitch"), 1e-6f);

        assertNull(y.getLocation("."), "there is no serialised Location to read -- hence the flat keys");
    }

    /** A file with no name key falls back to the filename, minus the extension. */
    @Test
    void theFilenameIsTheFallbackName() {
        assertEquals("spawn", "spawn.yml".replaceAll("(?i)\\.yml$", ""));
        assertEquals("nether_hub", "nether_hub.YML".replaceAll("(?i)\\.yml$", ""));
        assertEquals("odd.name", "odd.name.yml".replaceAll("(?i)\\.yml$", ""),
                "only the trailing extension goes");
    }

    /**
     * Warp names go through the same cleaner as home names, so /setwarp and the per-warp
     * permission node cannot disagree about what a warp is called.
     */
    @Test
    void warpNamesNormaliseLikeHomeNames() {
        assertEquals("spawn", Homes.clean("Spawn"));
        assertEquals("spawn", Homes.clean("  SPAWN  "));
        assertEquals("nether-hub_2", Homes.clean("Nether-Hub_2"));
        assertEquals("shop", Homes.clean("shop!!"), "punctuation dropped, not rejected");
        assertEquals("", Homes.clean("***"), "nothing usable left, so /setwarp refuses it");
        assertEquals("", Homes.clean(null));
    }

    /** A permission node is built from the lowercased name, so it can never be case-dependent. */
    @Test
    void thePerWarpNodeIsCaseInsensitive() {
        assertEquals("kremlin.warp.spawn", "kremlin.warp." + "Spawn".toLowerCase(java.util.Locale.ROOT));
        assertEquals("kremlin.warp.spawn", "kremlin.warp." + "SPAWN".toLowerCase(java.util.Locale.ROOT));
    }
}
