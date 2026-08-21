package me.lawsonhart.kremlin.teleport;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Named server-wide destinations: /warp, /setwarp, /delwarp, /warps.
 *
 * The teleport goes through {@link Combat#startWarmup} like /home does, so a warp is not a way
 * around the combat rules. Existing warps are read out of plugins/Essentials/warps on first start,
 * where each warp is a file of its own.
 */
public final class Warps implements CommandExecutor, TabCompleter {

    static final Set<String> GO = Set.of("warp", "ewarp");
    /** Every label this class answers to, for {@code Combat.ours}. */
    public static final Set<String> LABELS = Set.of(
            "warp", "ewarp", "warps", "ewarps", "warplist",
            "setwarp", "esetwarp", "createwarp", "delwarp", "edelwarp", "remwarp", "removewarp");
    private static final Set<String> LIST = Set.of("warps", "ewarps", "warplist");
    private static final Set<String> SET = Set.of("setwarp", "esetwarp", "createwarp");
    private static final Set<String> DEL = Set.of("delwarp", "edelwarp", "remwarp", "removewarp");

    private final Combat plugin;
    private final File file;

    /** Name -> where it goes. TreeMap so /warps is stable and lookup is case-insensitive. */
    private final Map<String, Location> warps = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private boolean perWarpPermission;

    public Warps(final Combat plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "warps.yml");
    }

    // ---------------------------------------------------------------- storage

    public void load() {
        perWarpPermission = plugin.getConfig().getBoolean("per-warp-permission", false);
        warps.clear();
        if (file.isFile()) {
            final YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            final ConfigurationSection section = y.getConfigurationSection("warps");
            if (section != null) {
                for (final String name : section.getKeys(false)) {
                    final Location where = section.getLocation(name);
                    if (where == null || where.getWorld() == null) {
                        plugin.getLogger().warning("Warp '" + name + "' has no loadable world, skipping it.");
                        continue;
                    }
                    warps.put(name, where);
                }
            }
        }
        importEssentials();
        plugin.getLogger().info("Loaded " + warps.size() + " warp(s).");
    }

    private synchronized void save() {
        final YamlConfiguration y = new YamlConfiguration();
        for (final Map.Entry<String, Location> e : warps.entrySet()) y.set("warps." + e.getKey(), e.getValue());
        try {
            y.save(file);
        } catch (final IOException ex) {
            plugin.getLogger().severe("Could not save warps.yml: " + ex);
        }
    }

    private void saveLater() {
        Bukkit.getAsyncScheduler().runNow(plugin.owner(), t -> save());
    }

    /**
     * Essentials keeps one file per warp, with the location spread across flat keys rather than
     * as a serialised Location. Only warps we do not already have are taken, so this is a no-op
     * on every start after the first even without the flag.
     */
    private void importEssentials() {
        if (plugin.getConfig().getBoolean("warps.imported", false)) return;
        plugin.getConfig().set("warps.imported", true);
        plugin.owner().saveConfig();

        final File dir = new File(plugin.getDataFolder().getParentFile(), "Essentials/warps");
        final File[] files = dir.isDirectory()
                ? dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml")) : null;
        if (files == null) return;

        int taken = 0;
        for (final File each : files) {
            final YamlConfiguration y = YamlConfiguration.loadConfiguration(each);
            final String name = y.getString("name", each.getName().replaceAll("(?i)\\.yml$", ""));
            if (name.isBlank() || warps.containsKey(name)) continue;
            final World world = y.getString("world") == null ? null : Bukkit.getWorld(y.getString("world"));
            if (world == null) {
                plugin.getLogger().warning("Warp '" + name + "' names a world we do not have, skipping it.");
                continue;
            }
            warps.put(name, new Location(world, y.getDouble("x"), y.getDouble("y"), y.getDouble("z"),
                    (float) y.getDouble("yaw"), (float) y.getDouble("pitch")));
            taken++;
        }
        if (taken > 0) {
            save();
            plugin.getLogger().info("Imported " + taken + " warp(s) from Essentials.");
        }
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);
        if (SET.contains(cmd)) return set(sender, args);
        if (DEL.contains(cmd)) return delete(sender, args);
        if (LIST.contains(cmd) || args.length == 0) return list(sender);

        if (!Perms.may(sender, "kremlin.warp", "essentials.warp")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        final Location where = warps.get(args[0]);
        if (where == null) {
            sender.sendMessage(plugin.msg("warp-unknown", Placeholder.unparsed("name", args[0])));
            return true;
        }
        // The stored key, not what they typed, so a per-warp node is not case-dependent.
        final String name = nameOf(args[0]);
        if (perWarpPermission && !Perms.may(sender, "kremlin.warp." + name.toLowerCase(Locale.ROOT),
                "essentials.warp." + name.toLowerCase(Locale.ROOT))) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }

        final Player target;
        if (args.length > 1) {
            if (!Perms.may(sender, "kremlin.warp.others", "essentials.warp.otherplayers")) {
                sender.sendMessage(plugin.msg("chat-no-permission"));
                return true;
            }
            target = Names.resolve(plugin.getServer(), args[1]);
            if (target == null) {
                sender.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[1])));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(plugin.msg("console-needs-player"));
            return true;
        }

        // Same gate and the same warmup as /home: a warp must not be a way out of a fight.
        final Component deny = plugin.denyTeleport(target);
        if (deny != null) {
            sender.sendMessage(deny);
            return true;
        }
        target.sendMessage(plugin.msg("warp-going", Placeholder.unparsed("name", name)));
        plugin.startWarmup(target, where.clone(), null, plugin.warmupFor(target));
        return true;
    }

    private boolean set(final CommandSender sender, final String[] args) {
        if (!Perms.may(sender, "kremlin.setwarp", "essentials.setwarp")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("console-needs-player"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.msg("setwarp-usage"));
            return true;
        }
        final String name = Homes.clean(args[0]);
        if (name.isEmpty()) {
            sender.sendMessage(plugin.msg("warp-bad-name", Placeholder.unparsed("name", args[0])));
            return true;
        }
        warps.put(name, p.getLocation().clone());
        saveLater();
        sender.sendMessage(plugin.msg("warp-set", Placeholder.unparsed("name", name)));
        return true;
    }

    private boolean delete(final CommandSender sender, final String[] args) {
        if (!Perms.may(sender, "kremlin.delwarp", "essentials.delwarp")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.msg("delwarp-usage"));
            return true;
        }
        if (warps.remove(args[0]) == null) {
            sender.sendMessage(plugin.msg("warp-unknown", Placeholder.unparsed("name", args[0])));
            return true;
        }
        saveLater();
        sender.sendMessage(plugin.msg("warp-deleted", Placeholder.unparsed("name", args[0])));
        return true;
    }

    private boolean list(final CommandSender sender) {
        if (!Perms.may(sender, "kremlin.warp", "essentials.warp")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        final List<String> reachable = reachable(sender);
        sender.sendMessage(reachable.isEmpty()
                ? plugin.msg("warps-none")
                : plugin.msg("warps", Placeholder.unparsed("count", String.valueOf(reachable.size())),
                        Placeholder.unparsed("warps", String.join(", ", reachable))));
        return true;
    }

    /** Only the warps this sender could actually use, so the list never offers a refusal. */
    private List<String> reachable(final CommandSender sender) {
        final List<String> out = new ArrayList<>();
        for (final String name : warps.keySet()) {
            if (perWarpPermission && !Perms.may(sender, "kremlin.warp." + name.toLowerCase(Locale.ROOT),
                    "essentials.warp." + name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(name);
        }
        return out;
    }

    /** The stored spelling of a warp, so messages and permissions agree on it. */
    private String nameOf(final String typed) {
        for (final String name : warps.keySet()) {
            if (name.equalsIgnoreCase(typed)) return name;
        }
        return typed;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);
        if (SET.contains(cmd)) return List.of();
        if (DEL.contains(cmd)) {
            return args.length == 1 ? Names.filter(args[0], new ArrayList<>(warps.keySet())) : List.of();
        }
        if (args.length == 1) return Names.filter(args[0], reachable(sender));
        if (args.length == 2 && Perms.may(sender, "kremlin.warp.others", "essentials.warp.otherplayers")) {
            return Names.filter(args[1], Names.online(plugin.getServer(), List.of()));
        }
        return List.of();
    }

    public String describe() {
        return warps.size() + " warp(s)";
    }
}
