package me.lawsonhart.kremlin.punish;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Durations;
import me.lawsonhart.kremlin.core.Perms;
import me.lawsonhart.kremlin.core.Users;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutes, the punishment history, and the IP trail /alts reads.
 *
 * Bans are deliberately NOT here. The server already keeps a ban list that does expiry, reasons,
 * the source and the kick screen, and applies it at login before any plugin gets a say -- so
 * {@link PunishCommands} puts bans there and this class only records that it happened. Mutes have
 * no such thing, so they live here and are enforced below.
 */
public final class Punishments implements Listener {

    /** One thing that was done to somebody. {@code until} is 0 for a one-off like a kick. */
    public record Entry(String type, UUID target, String reason, String by, long at, long until) {

        public boolean active() {
            return until == Durations.PERMANENT || until > System.currentTimeMillis();
        }

        /** Millis left, {@link Durations#PERMANENT} for forever, 0 once it has run out. */
        public long remaining() {
            if (until == Durations.PERMANENT) return Durations.PERMANENT;
            return Math.max(0L, until - System.currentTimeMillis());
        }
    }

    private static final String IPS = "ips";

    private final Combat plugin;
    private final Users users;
    private final File file;

    /** uuid -> their live mute. Expired ones are dropped as they are read. */
    private final Map<UUID, Entry> mutes = new ConcurrentHashMap<>();
    private final List<Entry> history = new ArrayList<>();
    private Set<String> muteCommands = Set.of();

    public Punishments(final Combat plugin, final Users users) {
        this.plugin = plugin;
        this.users = users;
        this.file = new File(plugin.getDataFolder(), "punishments.yml");
    }

    // ---------------------------------------------------------------- storage

    public void load() {
        final Set<String> blocked = new LinkedHashSet<>();
        for (final String s : plugin.getConfig().getStringList("mute-commands")) {
            blocked.add(s.toLowerCase(Locale.ROOT));
        }
        muteCommands = blocked;

        mutes.clear();
        history.clear();
        if (!file.isFile()) return;
        final YamlConfiguration y = YamlConfiguration.loadConfiguration(file);

        final ConfigurationSection muted = y.getConfigurationSection("mutes");
        if (muted != null) {
            for (final String key : muted.getKeys(false)) {
                final UUID id = parse(key);
                final ConfigurationSection sec = muted.getConfigurationSection(key);
                if (id == null || sec == null) continue;
                mutes.put(id, new Entry("mute", id, sec.getString("reason", ""),
                        sec.getString("by", "?"), sec.getLong("at"), sec.getLong("until")));
            }
        }
        for (final Map<?, ?> raw : y.getMapList("history")) {
            final UUID id = parse(str(raw, "target", ""));
            if (id == null) continue;
            history.add(new Entry(str(raw, "type", "?"), id, str(raw, "reason", ""),
                    str(raw, "by", "?"), num(raw, "at"), num(raw, "until")));
        }
        plugin.getLogger().info("Loaded " + mutes.size() + " mute(s) and " + history.size() + " history entry(s).");
    }

    /**
     * Reading a wildcard map: getOrDefault cannot take a String default against a captured
     * value type, and a row written by an older version may simply be missing a key.
     */
    public static String str(final Map<?, ?> row, final String key, final String fallback) {
        final Object value = row.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    static long num(final Map<?, ?> row, final String key) {
        return row.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    private static UUID parse(final String s) {
        try {
            return UUID.fromString(s);
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    private synchronized void save() {
        final YamlConfiguration y = new YamlConfiguration();
        for (final Map.Entry<UUID, Entry> e : mutes.entrySet()) {
            final String at = "mutes." + e.getKey() + ".";
            y.set(at + "reason", e.getValue().reason());
            y.set(at + "by", e.getValue().by());
            y.set(at + "at", e.getValue().at());
            y.set(at + "until", e.getValue().until());
        }
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final Entry entry : List.copyOf(history)) {
            rows.add(Map.of("type", entry.type(), "target", entry.target().toString(),
                    "reason", entry.reason(), "by", entry.by(),
                    "at", entry.at(), "until", entry.until()));
        }
        y.set("history", rows);
        try {
            y.save(file);
        } catch (final IOException ex) {
            plugin.getLogger().severe("Could not save punishments.yml: " + ex);
        }
    }

    private void saveLater() {
        Bukkit.getAsyncScheduler().runNow(plugin.owner(), t -> save());
    }

    public void saveNow() {
        save();
    }

    // ---------------------------------------------------------------- mutes

    /** Their live mute, or null. Expired entries are cleaned up as they are found. */
    public Entry muteOf(final UUID id) {
        final Entry entry = mutes.get(id);
        if (entry == null) return null;
        if (entry.active()) return entry;
        mutes.remove(id);
        saveLater();
        return null;
    }

    public void mute(final UUID id, final long millis, final String reason, final String by) {
        final long until = millis == Durations.PERMANENT
                ? Durations.PERMANENT : System.currentTimeMillis() + millis;
        final Entry entry = new Entry("mute", id, reason, by, System.currentTimeMillis(), until);
        mutes.put(id, entry);
        record(entry);
    }

    public boolean unmute(final UUID id) {
        final boolean had = mutes.remove(id) != null;
        if (had) saveLater();
        return had;
    }

    /**
     * Told about every entry as it is recorded.
     *
     * A hook rather than a direct call, so this package knows nothing about Discord -- and so a
     * punishment added later is mirrored without anybody remembering to mirror it.
     */
    private java.util.function.Consumer<Entry> onRecord = entry -> { };

    public void onRecord(final java.util.function.Consumer<Entry> listener) {
        this.onRecord = listener;
    }

    /** Adds to the audit trail. Everything that punishes somebody goes through here. */
    public void record(final Entry entry) {
        history.add(entry);
        saveLater();
        try {
            onRecord.accept(entry);
        } catch (final Throwable t) {
            // A listener that throws must not undo the punishment that was just applied.
            plugin.getLogger().warning("A punishment listener failed: " + t);
        }
    }

    public List<Entry> historyOf(final UUID id) {
        final List<Entry> out = new ArrayList<>();
        for (final Entry entry : List.copyOf(history)) {
            if (entry.target().equals(id)) out.add(entry);
        }
        out.sort((a, b) -> Long.compare(b.at(), a.at()));
        return out;
    }

    // ---------------------------------------------------------------- enforcement

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final Entry mute = muteOf(event.getPlayer().getUniqueId());
        if (mute == null) return;
        event.setCancelled(true);
        tell(event.getPlayer(), mute);
    }

    /**
     * A mute that only covered chat would be worth very little: /msg, /me and whatever the server
     * uses for faction chat all reach the same people. The list is config, because which commands
     * count as talking is a per-server question.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        if (muteCommands.isEmpty()) return;
        final String root = Combat.rootCommand(event.getMessage());
        if (!muteCommands.contains(root)) return;
        final Entry mute = muteOf(event.getPlayer().getUniqueId());
        if (mute == null) return;
        event.setCancelled(true);
        tell(event.getPlayer(), mute);
    }

    private void tell(final Player p, final Entry mute) {
        p.sendMessage(mute.remaining() == Durations.PERMANENT
                ? plugin.msg("muted-forever", Placeholder.unparsed("reason", mute.reason()))
                : plugin.msg("muted", Placeholder.unparsed("reason", mute.reason()),
                        Placeholder.unparsed("time", Durations.describe(mute.remaining()))));
    }

    /**
     * The IP trail /alts reads.
     *
     * Recorded at pre-login, which is async and off the region threads by design, and the only
     * point where the address is known for certain before anything else can act on the join.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        final String ip = event.getAddress().getHostAddress();
        final UUID id = event.getUniqueId();
        final Set<String> seen = new LinkedHashSet<>(users.list(id, IPS));
        if (seen.add(ip)) users.set(id, IPS, seen);
        users.set(id, "last-ip", ip);
    }

    /** Everyone who has ever joined from an address this player has also used. */
    public List<UUID> altsOf(final UUID id) {
        final Set<String> theirs = new LinkedHashSet<>(users.list(id, IPS));
        final List<UUID> out = new ArrayList<>();
        if (theirs.isEmpty()) return out;
        for (final UUID other : users.ids()) {
            if (other.equals(id)) continue;
            for (final String ip : users.list(other, IPS)) {
                if (theirs.contains(ip)) {
                    out.add(other);
                    break;
                }
            }
        }
        return out;
    }

    /** Nobody may punish somebody carrying the exempt node -- the ladder respects it too. */
    public boolean exempt(final Player target) {
        return Perms.may(target, "kremlin.punish.exempt", "essentials.punish.exempt");
    }

    public String describe() {
        return mutes.size() + " mute(s)";
    }
}
