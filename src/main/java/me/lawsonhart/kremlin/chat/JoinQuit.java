package me.lawsonhart.kremlin.chat;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Format;
import me.lawsonhart.kremlin.core.VaultHook;
import me.lawsonhart.kremlin.core.Perms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Join, quit and first-join messages.
 *
 * The config keys are Essentials' -- {@code custom-join-message}, {@code custom-quit-message} and
 * {@code newbies.announce-format} -- so an existing setup carries over as written, {PLAYER} and
 * all. "none" keeps the vanilla message and "" hides it, exactly as Essentials documents.
 */
public final class JoinQuit implements Listener {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();
    /** Essentials' sentinel for "leave the vanilla message alone". */
    private static final String VANILLA = "none";

    private final Combat plugin;
    private final VaultHook vault;

    private String join = VANILLA;
    private String quit = VANILLA;
    private String firstJoin = "";

    public JoinQuit(final Combat plugin, final VaultHook vault) {
        this.plugin = plugin;
        this.vault = vault;
    }

    public void load() {
        join = plugin.getConfig().getString("custom-join-message", VANILLA);
        quit = plugin.getConfig().getString("custom-quit-message", VANILLA);
        firstJoin = plugin.getConfig().getString("newbies.announce-format", "");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(final PlayerJoinEvent event) {
        final Player p = event.getPlayer();
        if (Perms.may(p, "kremlin.silentjoin", "essentials.silentjoin")) {
            event.joinMessage(null);
            return;
        }
        // A player who has played before but is joining for the first time this install would be
        // announced as new; hasPlayedBefore is the same check Essentials makes.
        final String template = !p.hasPlayedBefore() && !firstJoin.isEmpty() ? firstJoin : join;
        apply(template, p, event::joinMessage);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(final PlayerQuitEvent event) {
        final Player p = event.getPlayer();
        if (Perms.may(p, "kremlin.silentquit", "essentials.silentquit")) {
            event.quitMessage(null);
            return;
        }
        apply(quit, p, event::quitMessage);
    }

    private void apply(final String template, final Player p, final java.util.function.Consumer<Component> set) {
        if (template == null || template.equalsIgnoreCase(VANILLA)) return;
        if (template.isEmpty()) {
            set.accept(null);
            return;
        }
        set.accept(Format.render(Format.tokens(template, values(p))));
    }

    private Map<String, String> values(final Player p) {
        final Map<String, String> values = new HashMap<>();
        values.put("PLAYER", LEGACY.serialize(p.displayName()));
        values.put("DISPLAYNAME", LEGACY.serialize(p.displayName()));
        values.put("USERNAME", p.getName());
        values.put("PREFIX", vault.prefix(p));
        values.put("SUFFIX", vault.suffix(p));
        values.put("ONLINE", String.valueOf(Bukkit.getOnlinePlayers().size()));
        values.put("UNIQUE", String.valueOf(Bukkit.getOfflinePlayers().length));
        return values;
    }
}
