package me.lawsonhart.kremlin.combat;

import me.lawsonhart.kremlin.Kremlin;
import me.lawsonhart.kremlin.chat.Broadcast;
import me.lawsonhart.kremlin.chat.ChatFormat;
import me.lawsonhart.kremlin.chat.ChatImport;
import me.lawsonhart.kremlin.chat.Ignore;
import me.lawsonhart.kremlin.chat.JoinQuit;
import me.lawsonhart.kremlin.chat.Msg;
import me.lawsonhart.kremlin.chat.Papi;
import me.lawsonhart.kremlin.core.VaultHook;
import me.lawsonhart.kremlin.core.Messages;
import me.lawsonhart.kremlin.core.Users;
import me.lawsonhart.kremlin.discord.Bot;
import me.lawsonhart.kremlin.discord.DiscordLog;
import me.lawsonhart.kremlin.discord.LinkCommands;
import me.lawsonhart.kremlin.discord.Links;
import me.lawsonhart.kremlin.discord.RoleSync;
import me.lawsonhart.kremlin.discord.TeamRoleCommand;
import me.lawsonhart.kremlin.info.InfoCommands;
import me.lawsonhart.kremlin.item.Enchant;
import me.lawsonhart.kremlin.item.ItemCommands;
import me.lawsonhart.kremlin.item.Powertool;
import me.lawsonhart.kremlin.options.OptionsGui;
import me.lawsonhart.kremlin.punish.PunishCommands;
import me.lawsonhart.kremlin.punish.PunishGui;
import me.lawsonhart.kremlin.punish.Punishments;
import me.lawsonhart.kremlin.teleport.TpCommands;
import me.lawsonhart.kremlin.teleport.Warps;
import me.lawsonhart.kremlin.player.InvSee;
import me.lawsonhart.kremlin.player.Lookup;
import me.lawsonhart.kremlin.player.Nicknames;
import me.lawsonhart.kremlin.player.PlayerCommands;
import me.lawsonhart.kremlin.player.PlayerList;
import me.lawsonhart.kremlin.teleport.Homes;
import me.lawsonhart.kremlin.core.TeamHook;
import me.lawsonhart.kremlin.teleport.Tpa;

import io.papermc.paper.plugin.configuration.PluginMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Combat tagging, the flee penalty and the teleport warmup every other feature teleports through.
 *
 * This was CombatPrev's plugin class. It is a listener now rather than a JavaPlugin, so it keeps
 * delegates named exactly like JavaPlugin's ({@link #getConfig()} and friends) - that is what lets
 * {@link Homes} and {@link Tpa} carry over untouched.
 */
public final class Combat implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** uuid -> epoch millis the combat tag expires */
    private final Map<UUID, Long> combat = new ConcurrentHashMap<>();
    /** uuid -> where the player last was, and when they arrived there */
    private final Map<UUID, Anchor> anchor = new ConcurrentHashMap<>();
    /** uuid -> where they were standing when last hit, for the flee check */
    private final Map<UUID, Anchor> fleeOrigin = new ConcurrentHashMap<>();
    /** victim uuid -> last attacker's display name, formatting and all */
    private final Map<UUID, Component> lastAttacker = new ConcurrentHashMap<>();
    /** players currently being shown an action bar, so we only clear it once */
    private final Set<UUID> barShown = ConcurrentHashMap.newKeySet();
    /** players whose warmup finished, so the follow-up teleport isn't warmed up again */
    private final Set<UUID> warmupCleared = ConcurrentHashMap.newKeySet();

    /** A player's position and the moment they arrived at it. */
    private record Anchor(String world, double x, double y, double z, long since) {

        static Anchor of(Location loc, long when) {
            return new Anchor(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), when);
        }

        boolean samePlace(Location loc) {
            return world.equals(loc.getWorld().getName())
                    && x == loc.getX() && y == loc.getY() && z == loc.getZ();
        }
    }

    private final Kremlin plugin;

    private long combatMillis;
    private long stillMillis;
    private long tpaWarmupMillis;
    private double fleeDistanceSq;
    private long fleeWindowMillis;
    private boolean killOnLog;
    private boolean clearInvuln;
    private Tpa tpa;
    private Set<String> blockedCommands = Set.of();
    private Set<PlayerTeleportEvent.TeleportCause> blockedCauses = Set.of();
    private OptionsGui options;
    private Warps warps;
    private TpCommands tpCommands;
    private Punishments punishments;
    private PunishCommands punishCommands;
    private PunishGui punishGui;
    private InfoCommands info;
    private TeamHook teamHook;
    private VaultHook vault;
    private Links links;
    private Bot bot;
    private LinkCommands linkCommands;
    private RoleSync roleSync;
    private DiscordLog discordLog;
    private Homes homes;
    private InvSee invsee;
    private PlayerList players;
    private NamespacedKey barStyleKey;
    private final Messages messages;
    private final Users users;
    private ChatFormat chatFormat;
    private JoinQuit joinQuit;
    private Msg msg;
    private Ignore ignore;
    private Broadcast broadcast;
    private Nicknames nicknames;
    private PlayerCommands playerCommands;
    private Lookup lookup;
    private ItemCommands itemCommands;
    private Enchant enchant;
    private Powertool powertool;

    public Combat(Kremlin plugin) {
        this.plugin = plugin;
        this.messages = new Messages(plugin);
        this.users = new Users(plugin);
    }

    // ------------------------------------------------------- JavaPlugin delegates

    /** The real plugin, for anything that needs a Plugin (schedulers, async saves). */
    public Plugin owner() {
        return plugin;
    }

    public FileConfiguration getConfig() {
        return plugin.getConfig();
    }

    public Logger getLogger() {
        return plugin.getLogger();
    }

    public Server getServer() {
        return plugin.getServer();
    }

    public File getDataFolder() {
        return plugin.getDataFolder();
    }

    public PluginMeta getPluginMeta() {
        return plugin.getPluginMeta();
    }

    // ---------------------------------------------------------------- lifecycle

    public void enable() {
        migrateFromCombatPrev();
        plugin.saveDefaultConfig();
        load();
        barStyleKey = new NamespacedKey(plugin, "bar-style");
        users.load();
        teamHook = new TeamHook(plugin);
        teamHook.onTeamHomeTeleport(plugin, this::teamHomeTeleport);
        options = new OptionsGui(this, users);
        getServer().getPluginManager().registerEvents(this, plugin);
        getServer().getPluginManager().registerEvents(options, plugin);
        tpa = new Tpa(this);
        tpa.load();
        getServer().getPluginManager().registerEvents(tpa, plugin);
        warps = new Warps(this);
        warps.load();
        tpCommands = new TpCommands(this, users, tpa);
        getServer().getPluginManager().registerEvents(tpCommands, plugin);
        homes = new Homes(this, teamHook);
        homes.load();
        getServer().getPluginManager().registerEvents(homes, plugin);
        invsee = new InvSee(this);
        invsee.load();
        getServer().getPluginManager().registerEvents(invsee, plugin);
        nicknames = new Nicknames(this, users);
        nicknames.load();
        getServer().getPluginManager().registerEvents(nicknames, plugin);
        playerCommands = new PlayerCommands(this);
        playerCommands.load();
        lookup = new Lookup(this, nicknames);
        itemCommands = new ItemCommands(this);
        itemCommands.load();
        enchant = new Enchant(this);
        enchant.load();
        powertool = new Powertool(this, users);
        getServer().getPluginManager().registerEvents(powertool, plugin);
        players = new PlayerList(this, teamHook, nicknames);
        getServer().getPluginManager().registerEvents(players, plugin);
        punishments = new Punishments(this, users);
        punishments.load();
        getServer().getPluginManager().registerEvents(punishments, plugin);
        punishCommands = new PunishCommands(this, users, punishments);
        punishCommands.load();
        punishGui = new PunishGui(this);
        punishGui.load();
        getServer().getPluginManager().registerEvents(punishGui, plugin);
        info = new InfoCommands(this);
        links = new Links(users);
        bot = new Bot(this, links);
        roleSync = new RoleSync(this, links, bot);
        roleSync.load();
        discordLog = new DiscordLog(this, bot);
        discordLog.load();
        punishments.onRecord(discordLog::punishment);
        linkCommands = new LinkCommands(this, links, bot, roleSync);
        bot.start(roleSync);
        getServer().getPluginManager().registerEvents(roleSync, plugin);
        getServer().getPluginManager().registerEvents(new TeamRoleCommand(this, bot, roleSync), plugin);
        roleSync.startTimer();
        registerChat();
        me.lawsonhart.kremlin.chat.Placeholders.install(this);
        // /reload or late enable: players are already on
        for (Player p : getServer().getOnlinePlayers()) startBar(p);
    }

    /**
     * Chat: formatting, join/quit, private messages and ignoring. Vault and PlaceholderAPI are
     * both looked up lazily inside their own hooks, so it does not matter that neither plugin has
     * necessarily enabled by the time this runs.
     */
    private void registerChat() {
        for (String key : ChatImport.run(plugin)) {
            getLogger().info("Imported " + key + " from plugins/Essentials/config.yml.");
        }
        vault = new VaultHook(getLogger());
        Papi papi = new Papi(getLogger());

        ignore = new Ignore(this, users);
        chatFormat = new ChatFormat(this, vault, papi, ignore);
        chatFormat.load();
        getServer().getPluginManager().registerEvents(chatFormat, plugin);

        joinQuit = new JoinQuit(this, vault);
        joinQuit.load();
        getServer().getPluginManager().registerEvents(joinQuit, plugin);

        msg = new Msg(this, users, ignore);
        getServer().getPluginManager().registerEvents(msg, plugin);
        broadcast = new Broadcast(this);
    }

    public Users getUsers() {
        return users;
    }

    public Msg getMsg() {
        return msg;
    }

    public Ignore getIgnore() {
        return ignore;
    }

    public Broadcast getBroadcast() {
        return broadcast;
    }

    public Nicknames getNicknames() {
        return nicknames;
    }

    public PlayerCommands getPlayerCommands() {
        return playerCommands;
    }

    public Lookup getLookup() {
        return lookup;
    }

    public TeamHook getTeamHook() {
        return teamHook;
    }

    public VaultHook getVault() {
        return vault;
    }

    public Links getLinks() {
        return links;
    }

    public Bot getBot() {
        return bot;
    }

    public DiscordLog getDiscordLog() {
        return discordLog;
    }

    public RoleSync getRoleSync() {
        return roleSync;
    }

    public LinkCommands getLinkCommands() {
        return linkCommands;
    }

    public InfoCommands getInfo() {
        return info;
    }

    public Punishments getPunishments() {
        return punishments;
    }

    public PunishCommands getPunishCommands() {
        return punishCommands;
    }

    public PunishGui getPunishGui() {
        return punishGui;
    }

    public OptionsGui getOptions() {
        return options;
    }

    public Warps getWarps() {
        return warps;
    }

    public TpCommands getTpCommands() {
        return tpCommands;
    }

    public ItemCommands getItemCommands() {
        return itemCommands;
    }

    public Enchant getEnchant() {
        return enchant;
    }

    public Powertool getPowertool() {
        return powertool;
    }

    public ChatFormat getChatFormat() {
        return chatFormat;
    }

    public void disable() {
        if (homes != null) homes.saveNow();
        if (invsee != null) invsee.saveNow();
        if (users != null) users.saveNow();
        if (bot != null) bot.stop();
        if (punishments != null) punishments.saveNow();
    }

    /** The files CombatPrev owned. claims.yml is not among them -- SimpleTeams took that. */
    static final List<String> MIGRATED_FILES = List.of("config.yml", "homes.yml");

    /**
     * Carry CombatPrev's files across the rename, once. Runs before saveDefaultConfig() so an
     * imported config.yml is the one that gets loaded.
     */
    private void migrateFromCombatPrev() {
        File old = new File(getDataFolder().getParentFile(), "CombatPrev");
        try {
            for (String name : migrate(old, getDataFolder(), MIGRATED_FILES)) {
                getLogger().info("Migrated " + name + " from plugins/CombatPrev.");
            }
        } catch (IOException ex) {
            getLogger().severe("Could not migrate from plugins/CombatPrev: " + ex);
        }
    }

    /**
     * Copies each named file that exists in {@code from} and is absent in {@code to}, and
     * returns what it copied.
     *
     * Copied rather than moved, so the old folder stays put as a rollback, and never over an
     * existing file -- that makes a second start a no-op and means it can never clobber homes
     * saved since the merge.
     */
    static List<String> migrate(File from, File to, List<String> names) throws IOException {
        if (!from.isDirectory()) return List.of();
        List<String> copied = new ArrayList<>();
        for (String name : names) {
            File source = new File(from, name);
            File target = new File(to, name);
            if (!source.isFile() || target.exists()) continue;
            target.getParentFile().mkdirs();
            Files.copy(source.toPath(), target.toPath());
            copied.add(name);
        }
        return copied;
    }

    private void load() {
        applyBundledDefaults();
        messages.load();
        if (chatFormat != null) chatFormat.load();
        if (nicknames != null) nicknames.load();
        if (playerCommands != null) playerCommands.load();
        if (itemCommands != null) itemCommands.load();
        if (enchant != null) enchant.load();
        if (warps != null) warps.load();
        if (punishments != null) punishments.load();
        if (punishCommands != null) punishCommands.load();
        if (punishGui != null) punishGui.load();
        if (roleSync != null) roleSync.load();
        if (discordLog != null) discordLog.load();
        if (joinQuit != null) joinQuit.load();
        combatMillis = getConfig().getLong("combat-seconds", 15) * 1000L;
        stillMillis = getConfig().getLong("stand-still-seconds", 7) * 1000L;
        double fleeDistance = getConfig().getDouble("stand-still-flee-distance", 100.0);
        fleeDistanceSq = fleeDistance * fleeDistance;
        fleeWindowMillis = getConfig().getLong("stand-still-window-seconds", 60) * 1000L;
        tpaWarmupMillis = getConfig().getLong("tpa-warmup-seconds", 3) * 1000L;
        killOnLog = getConfig().getBoolean("kill-on-combat-log", true);
        clearInvuln = getConfig().getBoolean("clear-invulnerability-after-teleport", true);

        Set<String> cmds = new HashSet<>();
        for (String s : getConfig().getStringList("blocked-commands")) cmds.add(s.toLowerCase(Locale.ROOT));
        blockedCommands = cmds;

        Set<PlayerTeleportEvent.TeleportCause> causes = new HashSet<>();
        for (String s : getConfig().getStringList("blocked-teleport-causes")) {
            try {
                causes.add(PlayerTeleportEvent.TeleportCause.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Unknown teleport cause in config: " + s + " -- that teleport is NOT blocked."
                        + (s.equalsIgnoreCase("CHORUS_FRUIT") ? " MC 26.2 renamed it to CONSUMABLE_EFFECT." : ""));
            }
        }
        blockedCauses = causes;
    }

    /**
     * Layer the jar's config.yml underneath the user's file. saveDefaultConfig() only writes
     * when the file is absent, so upgrading the jar leaves an old config in place -- without
     * this, every key added after their file was generated reads back empty.
     */
    private void applyBundledDefaults() {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return;
            getConfig().setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            getLogger().warning("Couldn't read bundled config defaults: " + ex);
        }
    }

    // ---------------------------------------------------------------- state

    static long remaining(Map<UUID, Long> m, UUID id) {
        Long until = m.get(id);
        if (until == null) return 0L;
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            m.remove(id);
            return 0L;
        }
        return left;
    }

    /**
     * Moving or clearing a team HQ mid-fight would let someone summon their team to the fight,
     * so it is gated like any other combat-blocked command. Not config-driven: an existing
     * blocked-commands list in someone's config.yml would silently miss a newly shipped entry.
     */
    private static final Set<String> TEAM_HOME_ADMIN = Set.of(
            "sethq", "setteamhome", "delhq", "delteamhome", "team sethome", "team delhome");

    /** "/essentials:Home foo" -> "home" */
    public static String rootCommand(String message) {
        String cmd = message.startsWith("/") ? message.substring(1) : message;
        cmd = cmd.split("\\s", 2)[0].toLowerCase(Locale.ROOT);
        int colon = cmd.indexOf(':');
        return colon >= 0 ? cmd.substring(colon + 1) : cmd;
    }

    /** The command and, where one is typed, its subcommand: "/team sethome" -> "team sethome". */
    public static String rootAndSub(String message) {
        String[] parts = message.trim().split("\s+");
        if (parts.length < 2) return null;
        return rootCommand(parts[0]) + " " + parts[1].toLowerCase(Locale.ROOT);
    }

    /**
     * The labels Kremlin answers itself, and which blocked-commands therefore does not apply to.
     *
     * Each of these checks combat where it matters -- the moment it would actually move you --
     * which is finer than refusing the moment it is typed. Denying a request, cancelling one you
     * sent, setting a home or opening a menu are not escapes from a fight, and none of them were
     * ever blocked: these commands used to be cancelled at LOWEST priority to beat Essentials to
     * the label, so {@link #onCommand} never saw them at all. Owning the labels properly must not
     * quietly start blocking them.
     */
    private static final List<Set<String>> OURS = List.of(
            Tpa.ASK, Tpa.ASK_HERE, Tpa.ACCEPT, Tpa.DENY, Tpa.CANCEL,
            Homes.GO, Homes.SET, Homes.DEL, Homes.RENAME, Homes.ADMIN, Homes.HUB_GO,
            InvSee.INVSEE, InvSee.ENDER,
            TpCommands.LABELS, Warps.LABELS);

    static boolean ours(String root) {
        for (Set<String> labels : OURS) {
            if (labels.contains(root)) return true;
        }
        return false;
    }

    /** Blocked while tagged: our own list, plus the team-home management commands. */
    boolean blocks(String message) {
        String root = rootCommand(message);
        if (ours(root)) return false;
        if (blockedCommands.contains(root) || TEAM_HOME_ADMIN.contains(root)) return true;
        String pair = rootAndSub(message);
        return pair != null && (blockedCommands.contains(pair) || TEAM_HOME_ADMIN.contains(pair));
    }

    public Tpa getTpa() {
        return tpa;
    }

    public Homes getHomes() {
        return homes;
    }

    public PlayerList getPlayers() {
        return players;
    }

    public InvSee getInvSee() {
        return invsee;
    }

    private void tag(Player victim, Player attacker) {
        boolean fresh = remaining(combat, victim.getUniqueId()) <= 0;
        long now = System.currentTimeMillis();
        combat.put(victim.getUniqueId(), now + combatMillis);
        // Where the fight was. Running far from here is what earns the extra warmup.
        fleeOrigin.put(victim.getUniqueId(), Anchor.of(victim.getLocation(), now));
        if (attacker != null) lastAttacker.put(victim.getUniqueId(), attacker.displayName());
        if (fresh) victim.sendMessage(msg("tagged"));
    }

    private void untag(UUID id) {
        combat.remove(id);
        lastAttacker.remove(id);
    }

    /**
     * Everything that could still punish or delay this player. Death and quitting both use it,
     * so dying really does end the fight rather than only hiding the action bar.
     */
    private void forget(UUID id) {
        untag(id);
        fleeOrigin.remove(id);
        warmupCleared.remove(id);
    }

    // ---------------------------------------------------------------- messages

    /**
     * The player somebody meant: online by name or nickname, then by a stored nickname, then by
     * a real name the server has cached.
     *
     * The nickname step is what lets staff punish or look up an offline player by the name they
     * actually see on the screen -- without it, /history only works if you know the account name
     * behind a nickname, which is exactly the thing you were trying to find out.
     */
    public java.util.UUID findPlayer(String query) {
        if (query == null || query.isBlank()) return null;
        Player online = me.lawsonhart.kremlin.core.Names.resolve(getServer(), query);
        if (online != null) return online.getUniqueId();
        java.util.UUID nicked = nicknames == null ? null : nicknames.findByNick(query);
        if (nicked != null) return nicked;
        return me.lawsonhart.kremlin.core.Names.offlineId(getServer(), query);
    }

    /**
     * The name to show for somebody in a message, formatting and all.
     *
     * Not {@code Names.plainName}: that serialises to plain text, which is right for matching a
     * typed name and wrong for showing one -- it renders a gradient nickname as grey letters.
     * Anything the player actually sees should come through here.
     */
    public Component displayName(org.bukkit.OfflinePlayer who) {
        Player online = who.getPlayer();
        if (online != null) return online.displayName();
        Component nick = nicknames == null ? null : nicknames.nickOf(who.getUniqueId());
        return nick != null ? nick : Component.text(who.getName() == null ? "?" : who.getName());
    }

    public Component displayName(Player who) {
        return who.displayName();
    }

    public Messages messages() {
        return messages;
    }

    /** Delegate so the call sites that grew up around it stay as they are. */
    public Component msg(String key, TagResolver... resolvers) {
        return messages.get(key, resolvers);
    }

    public static String secs(long millis) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0L, millis) / 1000.0);
    }

    /**
     * Millis left before this player counts as having stood still long enough. Sampling and
     * asking are the same operation: any position change resets the clock, so calling this
     * from the ticker and from a command check both keep the anchor honest.
     *
     * Deliberately not a PlayerMoveEvent listener -- that's the hottest event in the game, and
     * the per-player action bar task is already ticking at 5Hz, which is far finer than a
     * 15-second window needs.
     */
    private void sampleAnchor(Player p) {
        UUID id = p.getUniqueId();
        Location loc = p.getLocation();
        Anchor a = anchor.get(id);
        if (a == null || !a.samePlace(loc)) {
            anchor.put(id, Anchor.of(loc, System.currentTimeMillis()));
        }
    }

    /**
     * Extra warmup for someone who took a fight and then ran for it.
     *
     * Zero for everyone else -- a player who hasn't been in a fight recently, or who hasn't
     * gone anywhere since, teleports on the normal short warmup. Changing world counts as
     * fleeing, since a cross-world distance is meaningless.
     */
    private long fleePenalty(Player p) {
        Anchor origin = fleeOrigin.get(p.getUniqueId());
        if (origin == null) return 0L;
        if (System.currentTimeMillis() - origin.since() > fleeWindowMillis) {
            fleeOrigin.remove(p.getUniqueId());
            return 0L;
        }
        Location loc = p.getLocation();
        return fled(origin.world(), origin.x(), origin.y(), origin.z(),
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), fleeDistanceSq)
                ? stillMillis : 0L;
    }

    /**
     * Whether the player has put enough ground between themselves and the fight to count as
     * having fled. A different world always counts -- a cross-world distance is meaningless.
     */
    static boolean fled(String originWorld, double ox, double oy, double oz,
                        String nowWorld, double nx, double ny, double nz, double distanceSq) {
        if (!originWorld.equals(nowWorld)) return true;
        double dx = nx - ox;
        double dy = ny - oy;
        double dz = nz - oz;
        return dx * dx + dy * dy + dz * dz >= distanceSq;
    }

    /** How long this player's next teleport should sit in warmup. */
    public long warmupFor(Player p) {
        return tpaWarmupMillis + fleePenalty(p);
    }

    /** True if this player's position changed since the anchor snapshot was taken. */
    private boolean movedSince(UUID id, Anchor snapshot) {
        Anchor now = anchor.get(id);
        return snapshot != null && now != null && now.since() != snapshot.since();
    }

    /**
     * Both players hold still for the warmup, then the teleport happens. An accepted tpa uses
     * this INSTEAD of the long stand-still gate -- a tpa is consensual and both ends are
     * standing there visibly, so it doesn't need the full anti-escape wait.
     */
    public void startWarmup(Player mover, Location destination, UUID partnerId, long warmupMillis) {
        Player partner = partnerId == null ? null : getServer().getPlayer(partnerId);
        // A home teleport has no second party, so it must not be told "both players".
        boolean solo = partnerId == null;
        Component notice = msg(solo ? "warmup-solo" : "tpa-warmup",
                Placeholder.unparsed("time", secs(warmupMillis)));
        mover.sendMessage(notice);
        if (partner != null) partner.sendMessage(notice);

        UUID moverId = mover.getUniqueId();
        Anchor moverStart = anchor.get(moverId);
        Anchor partnerStart = partnerId == null ? null : anchor.get(partnerId);
        long deadline = System.currentTimeMillis() + warmupMillis;

        // Ticks every quarter second so the title counts down instead of sitting still.
        // runAtFixedRate's retired callback covers the mover logging out mid-warmup.
        mover.getScheduler().runAtFixedRate(owner(), task -> {
            Player other = partnerId == null ? null : getServer().getPlayer(partnerId);

            if (movedSince(moverId, moverStart) || (partnerId != null && movedSince(partnerId, partnerStart))) {
                task.cancel();
                Component cancelled = msg(solo ? "warmup-solo-moved" : "tpa-moved");
                mover.clearTitle();
                mover.sendMessage(cancelled);
                if (other != null) {
                    other.clearTitle();
                    other.sendMessage(cancelled);
                }
                return;
            }

            long left = deadline - System.currentTimeMillis();
            if (left > 0) {
                Title countdown = Title.title(
                        msg("tpa-title", Placeholder.unparsed("time", secs(left))),
                        msg("tpa-subtitle", Placeholder.unparsed("time", secs(left))),
                        // No fade, and a stay just longer than the update interval: the title
                        // is replaced every tick-round, so any fade would strobe.
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(500), Duration.ZERO));
                mover.showTitle(countdown);
                if (other != null) other.showTitle(countdown);
                return;
            }

            task.cancel();
            mover.clearTitle();
            if (other != null) other.clearTitle();
            // Let our own follow-up teleport past the stand-still gate; it just waited.
            warmupCleared.add(moverId);
            mover.teleportAsync(destination);
        }, null, 1L, 5L);
    }

    /**
     * TeamHomeTeleportEvent, wearing our rules: combat refuses it outright, fleeing lengthens
     * it, and everyone else gets the same base warmup /hq has. The commands are handled in
     * {@link Homes} and never get this far -- this only catches another plugin calling
     * SimpleTeams' API directly. We finish the teleport ourselves rather than handing back, so
     * its event never fires a second time.
     */
    private void teamHomeTeleport(Player player, Location destination) {
        Component deny = denyTeleport(player);
        if (deny != null) {
            player.sendMessage(deny);
            return;
        }
        player.sendMessage(msg("home-going", Placeholder.unparsed("name", Homes.HQ)));
        startWarmup(player, destination, null, warmupFor(player));
    }

    /** Millis left on this player's combat tag, 0 when they are not tagged. */
    public long combatLeft(Player p) {
        return remaining(combat, p.getUniqueId());
    }

    /** Combat tag only. This is what gates the blocked command list. */
    public Component denyCombat(Player p) {
        long c = remaining(combat, p.getUniqueId());
        return c > 0 ? msg("deny-combat", Placeholder.unparsed("time", secs(c))) : null;
    }

    /** Kept as the single entry point teleport code asks before moving anyone. */
    public Component denyTeleport(Player p) {
        return denyCombat(p);
    }

    // ---------------------------------------------------------------- action bar

    /** Folia: per-entity scheduler, runs on whatever region owns the player and dies with them. */
    private void startBar(Player p) {
        p.getScheduler().runAtFixedRate(owner(), task -> bar(p), null, 1L, 4L);
    }

    /** How much the action bar shows. Stored per player, changed from /kremlin bar. */
    public enum BarStyle {
        FULL("Full", "COMBAT 12.4s | last hit by Steve"),
        SHORT("Short (default)", "PvP 12.4"),
        OFF("Off", "nothing at all");

        public final String label;
        public final String example;

        BarStyle(String label, String example) {
            this.label = label;
            this.example = example;
        }
    }

    /**
     * Where CombatPrev wrote the same byte. A NamespacedKey's namespace is the plugin's name, so
     * the rename would have orphaned every player's choice in their own player data - this is
     * read once per player and carried forward under the new key.
     */
    private static final NamespacedKey LEGACY_BAR_STYLE = NamespacedKey.fromString("combatprev:bar-style");

    /**
     * Kept in the player's own persistent data, so it survives relogs and rides along with
     * player data -- no extra file, no cleanup when someone stops playing.
     */
    public BarStyle styleOf(Player p) {
        PersistentDataContainer data = p.getPersistentDataContainer();
        Byte b = data.get(barStyleKey, PersistentDataType.BYTE);
        if (b == null && LEGACY_BAR_STYLE != null) {
            b = data.get(LEGACY_BAR_STYLE, PersistentDataType.BYTE);
            // Runs on the region that owns this player (entity scheduler or their own click).
            if (b != null) data.set(barStyleKey, PersistentDataType.BYTE, b);
        }
        // Unset means they've never chosen -- short is the default. Deliberately not done by
        // reordering the enum, since the stored value is the ordinal.
        if (b == null) return BarStyle.SHORT;
        BarStyle[] all = BarStyle.values();
        return b >= 0 && b < all.length ? all[b] : BarStyle.SHORT;
    }

    public BarStyle cycleStyle(Player p) {
        BarStyle next = BarStyle.values()[(styleOf(p).ordinal() + 1) % BarStyle.values().length];
        p.getPersistentDataContainer().set(barStyleKey, PersistentDataType.BYTE, (byte) next.ordinal());
        return next;
    }

    private void bar(Player p) {
        UUID id = p.getUniqueId();
        long c = remaining(combat, id);
        // Always sample, even with the bar switched off -- the warmup's move check reads it.
        sampleAnchor(p);
        BarStyle style = styleOf(p);

        if (c <= 0 || style == BarStyle.OFF) {
            if (barShown.remove(id)) p.sendActionBar(Component.empty());
            // The "no longer in combat" chat line still fires with the bar switched off.
            if (c <= 0 && lastAttacker.remove(id) != null) p.sendMessage(msg("ended"));
            return;
        }

        boolean brief = style == BarStyle.SHORT;
        Component out = Component.empty();
        if (c > 0) {
            out = msg(brief ? "combat-short" : "combat",
                    Placeholder.unparsed("time", secs(c)),
                    Placeholder.component("attacker", lastAttacker.getOrDefault(id, Component.text("unknown"))));
        }
        p.sendActionBar(out);
        barShown.add(id);
    }

    // ---------------------------------------------------------------- listeners

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (e.getDamager() instanceof Player p) {
            attacker = p;
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null || attacker.equals(victim)) return;

        tag(victim, attacker);
        tag(attacker, victim);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        boolean gated = blockedCauses.contains(e.getCause());

        // Combat blocks everything, an accepted tpa included.
        if (gated) {
            Component deny = denyCombat(p);
            if (deny != null) {
                e.setCancelled(true);
                warmupCleared.remove(id);
                p.sendMessage(deny);
                return;
            }
        }

        // Teleports we don't own (/spawn, /back, warps, portals) still get the flee penalty,
        // but as a warmup rather than a refusal. Only wrap when there IS a penalty, otherwise
        // every ordinary teleport would newly grow a delay.
        boolean ours = warmupCleared.remove(id);
        if (!ours && gated && fleePenalty(p) > 0) {
            e.setCancelled(true);
            startWarmup(p, e.getTo().clone(), null, warmupFor(p));
            return;
        }

        if (!clearInvuln) return;
        // No teleporting into a safety bubble. Runs on the destination region next tick.
        p.getScheduler().run(owner(), t -> {
            p.setInvulnerable(false);
            p.setNoDamageTicks(0);
        }, null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!blocks(e.getMessage())) return;

        // Every real teleport also fires PlayerTeleportEvent, where the flee warmup lives.
        // Commands themselves only need the combat gate.
        Component deny = denyCombat(e.getPlayer());
        if (deny == null) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(deny);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        startBar(e.getPlayer());
    }

    /** uuid -> we are the ones killing them, so vanilla's death message is ours to silence */
    private final Set<UUID> punishing = ConcurrentHashMap.newKeySet();

    /**
     * Dying ends the fight outright: no tag, no flee penalty, nothing left to punish. Quitting
     * from the death screen is therefore free, which is the whole point.
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        // A combat-log kill already announces itself. Without this the player gets vanilla's
        // death message AND the broadcast below -- two lines for one death.
        if (punishing.contains(id)) e.deathMessage(null);
        forget(id);
    }

    /** Fires for kicks too. Combat logging = death + full inventory on the floor. */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        anchor.remove(id);
        tpa.forget(id);
        barShown.remove(id);

        boolean tagged = remaining(combat, id) > 0;
        forget(id);
        if (!tagged || !killOnLog) return;

        // Already dead - they lost the fight, that IS the punishment. Killing a corpse asks the
        // server to run die() a second time, which is where the doubled death message came from.
        if (p.isDead() || p.getHealth() <= 0.0) return;

        punishing.add(id);
        try {
            Location loc = p.getLocation();
            // PlayerInventory.getContents() is all 41 slots: hotbar, main, armour, offhand.
            for (ItemStack item : p.getInventory().getContents()) {
                if (item != null && !item.getType().isAir()) loc.getWorld().dropItemNaturally(loc, item);
            }
            p.getInventory().clear();
            p.setHealth(0.0);
        } catch (Throwable t) {
            getLogger().warning("Failed to punish combat log for " + p.getName() + ": " + t);
        } finally {
            punishing.remove(id);
        }
        Bukkit.broadcast(msg("combat-log", Placeholder.component("player", p.displayName())));
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        switch (sub) {
            case "bar" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("Players only."));
                    return true;
                }
                options.open(p);
            }
            case "reload" -> {
                if (!mayAdmin(sender)) {
                    sender.sendMessage(Component.text("No permission."));
                    return true;
                }
                plugin.reloadConfig();
                load();
                tpa.load();
                homes.load();
                invsee.load();
                sender.sendMessage(msg("reloaded"));
            }
            case "importhomes" -> {
                if (!mayAdmin(sender)) {
                    sender.sendMessage(Component.text("No permission."));
                    return true;
                }
                // Dry run unless explicitly confirmed -- this touches everyone's homes.
                boolean apply = args.length > 1 && args[1].equalsIgnoreCase("confirm");
                Homes.ImportResult r = homes.importEssentials(apply);
                sender.sendMessage(Component.text((apply ? "Imported: " : "Dry run: ") + r.summary()));
                if (!apply) {
                    sender.sendMessage(Component.text("Nothing changed. Run /kremlin importhomes confirm to apply."));
                } else {
                    getLogger().info("Essentials home import applied: " + r.summary());
                }
            }
            default -> {
                sender.sendMessage(Component.text("Kremlin " + getPluginMeta().getVersion()
                        + " | flee +" + stillMillis / 1000 + "s past "
                        + (long) Math.sqrt(fleeDistanceSq) + " blocks within " + fleeWindowMillis / 1000 + "s"
                        + " | tpa warmup " + tpaWarmupMillis / 1000 + "s"
                        + " | combat " + combatMillis / 1000 + "s"
                        + " | tpa " + tpa.describe()
                        + " | homes " + homes.describe()
                        + " | invsee " + invsee.describe()
                        + " | " + chatFormat.describe()
                        + " | " + nicknames.describe()
                        + " | " + warps.describe()
                        + " | " + punishments.describe()
                        + " | " + bot.describe()
                        + " | " + discordLog.describe()));
                if (sender instanceof Player p) {
                    long c = remaining(combat, p.getUniqueId());
                    sender.sendMessage(Component.text("you: combat " + secs(c) + "s"
                            + " | flee penalty " + secs(fleePenalty(p)) + "s"
                            + " | next warmup " + secs(warmupFor(p)) + "s"
));
                    Component deny = denyTeleport(p);
                    sender.sendMessage(deny != null ? deny : Component.text("You could teleport right now."));
                }
                sender.sendMessage(Component.text("/kremlin bar | reload | importhomes   -   /home, /tpa, /nickg, /invsee, /playerlist"));
            }
        }
        return true;
    }

    /** The old node still grants it, so nothing in LuckPerms has to be redone. */
    private static boolean mayAdmin(CommandSender sender) {
        return sender.hasPermission("kremlin.reload") || sender.hasPermission("combatprev.reload");
    }
}
