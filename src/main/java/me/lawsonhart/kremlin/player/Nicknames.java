package me.lawsonhart.kremlin.player;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.EssentialsImport;
import me.lawsonhart.kremlin.core.Format;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import me.lawsonhart.kremlin.core.Users;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Nicknames. This is where Kremlin stops needing EssentialsX at all.
 *
 * Until now /nickg built a nickname and handed it to Essentials' /nick to store and apply, and
 * the player list read offline nicknames back out of Essentials' userdata. Both now go through
 * here, into users.yml.
 *
 * Nicknames are stored as MiniMessage -- what the gradient builder already produces -- rather
 * than as the legacy colour codes Essentials wrote, because a gradient cannot be expressed in
 * legacy codes without flattening it to one colour per character. Existing Essentials nicknames
 * are converted on the way in, so nobody loses theirs.
 */
public final class Nicknames implements Listener, CommandExecutor, TabCompleter {

    /** The users.yml key. */
    static final String KEY = "nick";
    private static final String IMPORTED = "nick.imported";

    /** Essentials' own settings, so an existing config keeps deciding what a nickname may be. */
    private static final String[][] SETTINGS = {
            {"nickname-prefix", "nick.prefix"},
            {"max-nick-length", "nick.max-length"},
            {"allowed-nicks-regex", "nick.allowed-regex"},
            {"ignore-colors-in-max-nick-length", "nick.ignore-colours-in-length"},
    };

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Combat plugin;
    private final Users users;

    private String prefix = "";
    private int maxLength = 32;
    private Pattern allowed = Pattern.compile("^[a-zA-Z_0-9§&<>:#-]+$");
    private boolean ignoreColoursInLength = true;

    public Nicknames(final Combat plugin, final Users users) {
        this.plugin = plugin;
        this.users = users;
    }

    public void load() {
        for (final String key : EssentialsImport.copy(plugin.owner(), IMPORTED, SETTINGS)) {
            plugin.getLogger().info("Imported " + key + " from plugins/Essentials/config.yml.");
        }
        prefix = plugin.getConfig().getString("nick.prefix", "");
        maxLength = plugin.getConfig().getInt("nick.max-length", 32);
        ignoreColoursInLength = plugin.getConfig().getBoolean("nick.ignore-colours-in-length", true);
        final String regex = plugin.getConfig().getString("nick.allowed-regex", "");
        if (!regex.isEmpty()) {
            try {
                allowed = Pattern.compile(regex);
            } catch (final RuntimeException ex) {
                plugin.getLogger().warning("nick.allowed-regex is not a valid pattern (" + ex
                        + ") -- falling back to the default.");
            }
        }
        importNicknames();
    }

    // ---------------------------------------------------------------- storage

    /** The stored nickname as MiniMessage, or null when they have none. */
    public String rawOf(final UUID id) {
        final String stored = users.text(id, KEY);
        return stored == null || stored.isBlank() ? null : stored;
    }

    /** The stored nickname rendered, or null. Works the same online or off. */
    public Component nickOf(final UUID id) {
        final String raw = rawOf(id);
        if (raw == null) return null;
        try {
            return MM.deserialize(raw);
        } catch (final ParsingException ex) {
            plugin.getLogger().warning("Nickname for " + id + " will not parse, ignoring it: " + ex);
            return null;
        }
    }

    /**
     * Whoever holds this nickname, or null. Matched on the rendered, stripped form, so typing
     * "Dalton" finds a player nicknamed with a gradient -- the same rule {@link Names#pickPlayer}
     * uses for online players, extended to people who are not on right now.
     *
     * ponytail: renders every stored nickname per lookup. That is once per command, not per tick;
     * cache it if users.yml ever gets big enough for it to show up.
     */
    public UUID findByNick(final String query) {
        if (query == null || query.isBlank()) return null;
        final String wanted = Names.bare(query);
        if (wanted.isEmpty()) return null;
        for (final UUID id : users.ids()) {
            final Component nick = nickOf(id);
            if (nick == null) continue;
            final String plain = PlainTextComponentSerializer.plainText().serialize(nick).trim();
            if (Names.bare(plain).equals(wanted)) return id;
        }
        return null;
    }

    /** Null clears it. Applies immediately when they are online. */
    public void set(final UUID id, final String miniMessage) {
        users.set(id, KEY, miniMessage);
        final Player online = plugin.getServer().getPlayer(id);
        if (online != null) apply(online);
    }

    /**
     * Push the stored nickname onto the player. Both the display name and the tab list name are
     * set, which is what Essentials' change-displayname and change-playerlist did together.
     */
    public void apply(final Player p) {
        final Component nick = nickOf(p.getUniqueId());
        final Component shown = nick == null
                ? Component.text(p.getName())
                : (prefix.isEmpty() ? nick : Format.render(prefix).append(nick));
        p.displayName(shown);
        p.playerListName(shown);
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.msg("nick-usage"));
            return true;
        }
        // Essentials' argument order: /nick <nick|off>, or /nick <player> <nick|off>.
        final boolean others = args.length > 1;
        final String wanted = args[others ? 1 : 0];

        final Player target;
        if (others) {
            target = Names.resolve(plugin.getServer(), args[0]);
            if (target == null) {
                sender.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[0])));
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(plugin.msg("nick-usage"));
            return true;
        }

        final String node = others ? "nick.others" : "nick";
        if (!Perms.may(sender, "kremlin." + node, "essentials." + node)) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }

        if (wanted.equalsIgnoreCase("off") || wanted.equalsIgnoreCase("none")
                || wanted.equalsIgnoreCase(target.getName())) {
            set(target.getUniqueId(), null);
            sender.sendMessage(plugin.msg("nick-cleared",
                    Placeholder.component("player", plugin.displayName(target))));
            return true;
        }

        final String refusal = refuse(sender, wanted);
        if (refusal != null) {
            sender.sendMessage(plugin.msg(refusal, Placeholder.unparsed("name", wanted),
                    Placeholder.unparsed("max", String.valueOf(maxLength))));
            return true;
        }

        final String mini = Format.legacyToMiniMessage(wanted);
        final Component preview;
        try {
            preview = MM.deserialize(mini);
        } catch (final ParsingException ex) {
            sender.sendMessage(plugin.msg("nick-bad", Placeholder.unparsed("name", wanted)));
            return true;
        }
        if (taken(target.getUniqueId(), preview)) {
            sender.sendMessage(plugin.msg("nick-taken", Placeholder.unparsed("name", wanted)));
            return true;
        }

        set(target.getUniqueId(), mini);
        sender.sendMessage(plugin.msg("nick-set", Placeholder.component("player", preview),
                Placeholder.unparsed("name", target.getName())));
        return true;
    }

    /** The message key to refuse with, or null when the nickname is fine. */
    private String refuse(final CommandSender sender, final String wanted) {
        if (!Perms.may(sender, "kremlin.nick.allowunsafe", "essentials.nick.allowunsafe")
                && !allowed.matcher(wanted).matches()) {
            return "nick-bad";
        }
        return tooLong(wanted, maxLength, ignoreColoursInLength) ? "nick-too-long" : null;
    }

    /**
     * Whether a nickname busts the limit. Colour codes are optionally not counted, which is
     * EssentialsX's ignore-colors-in-max-nick-length -- otherwise a single gradient eats the whole
     * budget in tags and nobody can set a nickname longer than about four letters.
     */
    static boolean tooLong(final String wanted, final int max, final boolean ignoreColours) {
        if (!ignoreColours) return wanted.length() > max;
        final String bare = PlainTextComponentSerializer.plainText()
                .serialize(Format.render(wanted)).trim();
        return bare.length() > max;
    }

    /**
     * A nickname that reads as somebody else's real name lets you intercept their /msg and /tpa,
     * so it is refused outright -- the same rule {@link Names#pickPlayer} enforces when resolving.
     */
    private boolean taken(final UUID self, final Component preview) {
        final String plain = Names.bare(
                PlainTextComponentSerializer.plainText().serialize(preview).trim());
        if (plain.isEmpty()) return true;
        for (final Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.getUniqueId().equals(self) && Names.bare(other.getName()).equals(plain)) return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String label, final String[] args) {
        if (args.length == 1) {
            final List<String> options = Names.online(plugin.getServer(), List.of("off"));
            return Names.filter(args[0], options);
        }
        return args.length == 2 ? Names.filter(args[1], List.of("off")) : List.of();
    }

    // ---------------------------------------------------------------- essentials data

    /**
     * Carries existing nicknames out of Essentials' userdata, once. They are stored there as
     * legacy colour codes, which {@link Format#legacyToMiniMessage} converts without loss --
     * a per-character gradient is exactly what those codes already encode.
     */
    private void importNicknames() {
        if (plugin.getConfig().getBoolean("nick.imported-names", false)) return;
        final File dir = EssentialsImport.userdata(plugin.owner());
        plugin.getConfig().set("nick.imported-names", true);
        plugin.owner().saveConfig();
        if (dir == null) return;

        final File[] files = dir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".yml"));
        if (files == null) return;
        int taken = 0;
        for (final File file : files) {
            final UUID id;
            try {
                id = UUID.fromString(file.getName().substring(0, file.getName().length() - 4));
            } catch (final IllegalArgumentException ex) {
                continue;
            }
            if (rawOf(id) != null) continue; // ours already wins
            final String nick = YamlConfiguration.loadConfiguration(file).getString("nickname");
            if (nick == null || nick.isBlank()) continue;
            users.set(id, KEY, Format.legacyToMiniMessage(nick));
            taken++;
        }
        if (taken > 0) plugin.getLogger().info("Imported " + taken + " nickname(s) from Essentials.");
    }

    public String describe() {
        return "nicknames (max " + maxLength + ")";
    }
}
