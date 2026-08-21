package me.lawsonhart.kremlin.core;

import org.bukkit.permissions.Permissible;

/**
 * Permission checks against several node names at once.
 *
 * Kremlin's own {@code kremlin.*} nodes are the names to configure, but CombatPrev's
 * {@code combatprev.*} and Essentials' {@code essentials.*} equivalents are honoured beside
 * them so an existing LuckPerms setup keeps working untouched across both renames.
 */
public final class Perms {

    private Perms() {
    }

    public static boolean may(final Permissible who, final String... nodes) {
        for (final String node : nodes) {
            if (who.hasPermission(node)) return true;
        }
        return false;
    }
}
