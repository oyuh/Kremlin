package me.lawsonhart.kremlin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The CombatPrev -> Kremlin data hand-over. This one moves players' homes, so "never
 * overwrite" is the property that matters: getting it wrong loses everyone's data.
 */
class MigrationTest {

    @Test
    void copiesTheOldFilesAcrossOnce(@TempDir Path root) throws IOException {
        File old = folder(root, "CombatPrev");
        File now = folder(root, "Kremlin");
        Files.writeString(new File(old, "config.yml").toPath(), "combat-seconds: 25\n");
        Files.writeString(new File(old, "homes.yml").toPath(), "players: {}\n");

        assertEquals(List.of("config.yml", "homes.yml"), Combat.migrate(old, now, Combat.MIGRATED_FILES));
        assertEquals("combat-seconds: 25\n", Files.readString(new File(now, "config.yml").toPath()),
                "the server owner's own settings, not the shipped defaults");
        assertEquals("players: {}\n", Files.readString(new File(now, "homes.yml").toPath()));

        assertTrue(new File(old, "homes.yml").isFile(), "copied, not moved -- the old folder is the rollback");
        assertEquals(List.of(), Combat.migrate(old, now, Combat.MIGRATED_FILES), "second start is a no-op");
    }

    @Test
    void neverOverwritesWhatIsAlreadyThere(@TempDir Path root) throws IOException {
        File old = folder(root, "CombatPrev");
        File now = folder(root, "Kremlin");
        Files.writeString(new File(old, "homes.yml").toPath(), "stale\n");
        Files.writeString(new File(now, "homes.yml").toPath(), "homes set since the merge\n");

        assertEquals(List.of(), Combat.migrate(old, now, Combat.MIGRATED_FILES));
        assertEquals("homes set since the merge\n", Files.readString(new File(now, "homes.yml").toPath()),
                "a home saved after the merge must survive a restart");
    }

    @Test
    void aFreshServerWithNoOldPluginIsFine(@TempDir Path root) throws IOException {
        File missing = new File(root.toFile(), "CombatPrev");
        File now = folder(root, "Kremlin");

        assertEquals(List.of(), Combat.migrate(missing, now, Combat.MIGRATED_FILES));
        assertEquals(0, now.listFiles().length, "nothing invented out of thin air");
    }

    @Test
    void copiesWhatExistsAndSkipsWhatDoesNot(@TempDir Path root) throws IOException {
        File old = folder(root, "CombatPrev");
        File now = folder(root, "Kremlin");
        Files.writeString(new File(old, "config.yml").toPath(), "combat-seconds: 15\n");
        // No homes.yml: nobody had set a home yet.

        assertEquals(List.of("config.yml"), Combat.migrate(old, now, Combat.MIGRATED_FILES));
        assertFalse(new File(now, "homes.yml").exists(), "no empty homes.yml conjured up");
    }

    @Test
    void createsTheTargetFolderIfTheServerHasNeverRunUs(@TempDir Path root) throws IOException {
        File old = folder(root, "CombatPrev");
        File now = new File(root.toFile(), "Kremlin"); // deliberately not created
        Files.writeString(new File(old, "homes.yml").toPath(), "players: {}\n");

        assertEquals(List.of("homes.yml"), Combat.migrate(old, now, Combat.MIGRATED_FILES));
        assertTrue(new File(now, "homes.yml").isFile());
    }

    private static File folder(Path root, String name) {
        File dir = new File(root.toFile(), name);
        assertTrue(dir.mkdirs());
        return dir;
    }
}
