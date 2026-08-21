package me.lawsonhart.kremlin.chat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * PlaceholderAPI, if it is installed.
 *
 * Needed because EssentialsChat resolves third-party placeholders written in its own brace
 * syntax, so a real config carries entries like
 * {@code format: '{simpleteams_prefix_formatted} {PREFIX}{DISPLAYNAME}&r: {MESSAGE}'}. Without
 * this the brace token would survive into chat as literal text.
 *
 * Reflective for the same reason as {@link VaultHook}: no compile-time dependency, and a server
 * without PlaceholderAPI simply gets the text back unchanged.
 */
public final class Papi {

    private final Logger log;
    private boolean looked;
    private Method setPlaceholders;
    private boolean warned;

    public Papi(final Logger log) {
        this.log = log;
    }

    private synchronized void find() {
        if (looked) return;
        looked = true;
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try {
            setPlaceholders = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                    .getMethod("setPlaceholders", Player.class, String.class);
            log.info("PlaceholderAPI found -- placeholders in chat formats will be resolved.");
        } catch (final Throwable t) {
            log.warning("PlaceholderAPI is installed but its API did not match (" + t + ").");
        }
    }

    public boolean present() {
        find();
        return setPlaceholders != null;
    }

    /** Returns {@code text} untouched when PlaceholderAPI is absent or unhappy. */
    public String apply(final Player player, final String text) {
        find();
        if (setPlaceholders == null) return text;
        try {
            final Object out = setPlaceholders.invoke(null, player, text);
            return out == null ? text : out.toString();
        } catch (final Throwable t) {
            if (!warned) {
                warned = true;
                log.warning("PlaceholderAPI lookup failed, leaving placeholders as they are: " + t);
            }
            return text;
        }
    }
}
