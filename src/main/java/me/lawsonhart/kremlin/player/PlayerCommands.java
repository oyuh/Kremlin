package me.lawsonhart.kremlin.player;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The short commands that all have the same shape: act on yourself, or on somebody else with a
 * second permission.
 *
 * They live together because each is a handful of lines and the self/others split is the only
 * interesting thing about any of them; a file each would be nine files of boilerplate around one
 * statement. The branch is on the label, exactly as in {@link Tpa} and {@link Homes}.
 */
public final class PlayerCommands implements CommandExecutor, TabCompleter {

    private static final Set<String> HEAL = Set.of("heal", "eheal");
    private static final Set<String> FEED = Set.of("feed", "efeed", "eat");
    private static final Set<String> KILL = Set.of("kill", "ekill", "suicide");
    private static final Set<String> FLY = Set.of("fly", "efly");
    private static final Set<String> SPEED = Set.of("speed", "espeed");
    private static final Set<String> SUDO = Set.of("sudo", "esudo");
    private static final Set<String> WEATHER = Set.of("weather", "eweather", "sky");

    /** Label -> the mode it sets. /gamemode reads the mode from its first argument instead. */
    private static final Map<String, GameMode> SHORTCUTS = Map.of(
            "gms", GameMode.SURVIVAL, "gmc", GameMode.CREATIVE,
            "gma", GameMode.ADVENTURE, "gmsp", GameMode.SPECTATOR);

    private static final Map<String, GameMode> MODES = Map.ofEntries(
            Map.entry("survival", GameMode.SURVIVAL), Map.entry("s", GameMode.SURVIVAL),
            Map.entry("0", GameMode.SURVIVAL),
            Map.entry("creative", GameMode.CREATIVE), Map.entry("c", GameMode.CREATIVE),
            Map.entry("1", GameMode.CREATIVE),
            Map.entry("adventure", GameMode.ADVENTURE), Map.entry("a", GameMode.ADVENTURE),
            Map.entry("2", GameMode.ADVENTURE),
            Map.entry("spectator", GameMode.SPECTATOR), Map.entry("sp", GameMode.SPECTATOR),
            Map.entry("3", GameMode.SPECTATOR));

    private final Combat plugin;

    private double maxWalkSpeed = 0.8;
    private double maxFlySpeed = 0.8;
    private boolean removeEffectsOnHeal = true;

    public PlayerCommands(final Combat plugin) {
        this.plugin = plugin;
    }

    public void load() {
        maxWalkSpeed = plugin.getConfig().getDouble("max-walk-speed", 0.8);
        maxFlySpeed = plugin.getConfig().getDouble("max-fly-speed", 0.8);
        removeEffectsOnHeal = plugin.getConfig().getBoolean("remove-effects-on-heal", true);
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);

        if (SUDO.contains(cmd)) return sudo(sender, args);
        if (WEATHER.contains(cmd)) return weather(sender, args);
        if (SPEED.contains(cmd)) return speed(sender, args);
        if (SHORTCUTS.containsKey(cmd)) return gamemode(sender, SHORTCUTS.get(cmd), args, 0);
        if (cmd.equals("gamemode") || cmd.equals("gm") || cmd.equals("egamemode")) {
            if (args.length == 0) {
                sender.sendMessage(plugin.msg("gamemode-usage"));
                return true;
            }
            final GameMode mode = mode(args[0]);
            if (mode == null) {
                sender.sendMessage(plugin.msg("gamemode-unknown", Placeholder.unparsed("mode", args[0])));
                return true;
            }
            return gamemode(sender, mode, args, 1);
        }

        // Everything left takes an optional player as its only argument.
        final String node = HEAL.contains(cmd) ? "heal" : FEED.contains(cmd) ? "feed"
                : KILL.contains(cmd) ? "kill" : "fly";
        final Player target = target(sender, args, 0, node);
        if (target == null) return true;
        final boolean self = sender instanceof Player p && p.getUniqueId().equals(target.getUniqueId());

        if (HEAL.contains(cmd)) heal(target);
        else if (FEED.contains(cmd)) feed(target);
        else if (KILL.contains(cmd)) {
            if (!self && Perms.may(target, "kremlin.kill.exempt", "essentials.kill.exempt")) {
                sender.sendMessage(plugin.msg("exempt", Placeholder.component("player", plugin.displayName(target))));
                return true;
            }
            target.setHealth(0.0);
        } else {
            target.setAllowFlight(!target.getAllowFlight());
            target.setFlying(target.getAllowFlight());
        }

        final String key = HEAL.contains(cmd) ? "healed" : FEED.contains(cmd) ? "fed"
                : KILL.contains(cmd) ? "killed"
                : target.getAllowFlight() ? "fly-on" : "fly-off";
        announce(sender, target, key, self);
        return true;
    }

    // ---------------------------------------------------------------- the commands

    private void heal(final Player target) {
        final var max = target.getAttribute(Attribute.MAX_HEALTH);
        target.setHealth(max == null ? 20.0 : max.getValue());
        target.setFoodLevel(20);
        target.setSaturation(20f);
        target.setFireTicks(0);
        if (removeEffectsOnHeal) {
            for (final PotionEffect effect : List.copyOf(target.getActivePotionEffects())) {
                target.removePotionEffect(effect.getType());
            }
        }
    }

    private void feed(final Player target) {
        target.setFoodLevel(20);
        target.setSaturation(20f);
    }

    private boolean speed(final CommandSender sender, final String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.msg("speed-usage"));
            return true;
        }
        // Essentials' shape: /speed [walk|fly] <0-10> [player]. The mode word is optional and,
        // when left out, means whichever the player is doing right now.
        int at = 0;
        Boolean flying = null;
        if (args[0].equalsIgnoreCase("walk")) { flying = false; at = 1; }
        else if (args[0].equalsIgnoreCase("fly")) { flying = true; at = 1; }
        if (args.length <= at) {
            sender.sendMessage(plugin.msg("speed-usage"));
            return true;
        }

        final double asked;
        try {
            asked = Double.parseDouble(args[at]);
        } catch (final NumberFormatException ex) {
            sender.sendMessage(plugin.msg("speed-usage"));
            return true;
        }
        final Player target = target(sender, args, at + 1, "speed");
        if (target == null) return true;

        final boolean fly = flying != null ? flying : target.isFlying();
        final double capped = speed(asked, fly ? maxFlySpeed : maxWalkSpeed,
                Perms.may(sender, "kremlin.speed.bypass", "essentials.speed.bypass"));
        if (fly) target.setFlySpeed((float) capped); else target.setWalkSpeed((float) capped);

        sender.sendMessage(plugin.msg("speed-set",
                Placeholder.unparsed("mode", fly ? "fly" : "walk"),
                Placeholder.unparsed("speed", String.valueOf(capped * 10.0)),
                Placeholder.component("player", plugin.displayName(target))));
        return true;
    }

    /**
     * What a typed 0-10 speed becomes for the API, which wants 0-1.
     *
     * The cap is EssentialsX's max-walk-speed / max-fly-speed, and it is a ratio in the same 0-1
     * space -- 0.8 means "/speed 10 is really 8". Out-of-range input is clamped rather than
     * refused, and setWalkSpeed throws on anything outside 0-1, so this must never let one past.
     */
    static double speed(final double asked, final double cap, final boolean bypass) {
        final double ratio = Math.max(0.0, Math.min(10.0, asked)) / 10.0;
        return bypass ? ratio : Math.min(ratio, Math.max(0.0, Math.min(1.0, cap)));
    }

    /** The game mode a typed word means, or null. Package-private so the aliases can be pinned. */
    static GameMode mode(final String word) {
        return MODES.get(word.toLowerCase(Locale.ROOT));
    }

    private boolean gamemode(final CommandSender sender, final GameMode mode,
                             final String[] args, final int at) {
        final String name = mode.name().toLowerCase(Locale.ROOT);
        if (!Perms.may(sender, "kremlin.gamemode." + name, "essentials.gamemode." + name,
                "kremlin.gamemode", "essentials.gamemode")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        final Player target = target(sender, args, at, "gamemode");
        if (target == null) return true;
        target.setGameMode(mode);

        final boolean self = sender instanceof Player p && p.getUniqueId().equals(target.getUniqueId());
        sender.sendMessage(plugin.msg(self ? "gamemode-self" : "gamemode-other",
                Placeholder.unparsed("mode", name),
                Placeholder.component("player", plugin.displayName(target))));
        if (!self) {
            target.sendMessage(plugin.msg("gamemode-self", Placeholder.unparsed("mode", name),
                    Placeholder.component("player", plugin.displayName(target))));
        }
        return true;
    }

    /**
     * /sudo runs a command as somebody else. The exempt node is what stops it being a way to
     * make a higher-ranked player run something on your behalf.
     */
    private boolean sudo(final CommandSender sender, final String[] args) {
        if (!Perms.may(sender, "kremlin.sudo", "essentials.sudo")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.msg("sudo-usage"));
            return true;
        }
        final Player target = Names.resolve(plugin.getServer(), args[0]);
        if (target == null) {
            sender.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[0])));
            return true;
        }
        if (Perms.may(target, "kremlin.sudo.exempt", "essentials.sudo.exempt")) {
            sender.sendMessage(plugin.msg("exempt", Placeholder.component("player", plugin.displayName(target))));
            return true;
        }

        final String line = String.join(" ", List.of(args).subList(1, args.length));
        sender.sendMessage(plugin.msg("sudo-ran", Placeholder.component("player", plugin.displayName(target)),
                Placeholder.unparsed("command", line)));
        // On the target's own region thread: the command runs as them, so it must run where they do.
        target.getScheduler().run(plugin.owner(), t -> {
            if (line.startsWith("c:")) target.chat(line.substring(2));
            else target.performCommand(line.startsWith("/") ? line.substring(1) : line);
        }, null);
        return true;
    }

    private boolean weather(final CommandSender sender, final String[] args) {
        if (!Perms.may(sender, "kremlin.weather", "essentials.weather")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.msg("weather-usage"));
            return true;
        }
        final String what = args[0].toLowerCase(Locale.ROOT);
        final boolean storm = what.equals("storm") || what.equals("rain") || what.equals("thunder");
        if (!storm && !what.equals("sun") && !what.equals("clear")) {
            sender.sendMessage(plugin.msg("weather-usage"));
            return true;
        }
        final World world = sender instanceof Player p ? p.getWorld()
                : plugin.getServer().getWorlds().isEmpty() ? null : plugin.getServer().getWorlds().get(0);
        if (world == null) {
            sender.sendMessage(plugin.msg("weather-usage"));
            return true;
        }

        // Seconds in the argument, ticks in the API. No duration means "until it changes on its own".
        int duration = 0;
        if (args.length > 1) {
            try {
                duration = Math.max(0, Integer.parseInt(args[1])) * 20;
            } catch (final NumberFormatException ignored) {
                // A duration we cannot read is treated as none given, not as a refusal.
            }
        }
        world.setStorm(storm);
        world.setThundering(storm && what.equals("thunder"));
        if (duration > 0) world.setWeatherDuration(duration);

        sender.sendMessage(plugin.msg(storm ? "weather-storm" : "weather-sun",
                Placeholder.unparsed("world", world.getName())));
        return true;
    }

    // ---------------------------------------------------------------- shared

    /**
     * The player this command is aimed at, having checked the right half of the permission pair.
     * Returns null once it has already explained itself to the sender.
     */
    private Player target(final CommandSender sender, final String[] args, final int at, final String node) {
        final boolean others = args.length > at;
        if (!Perms.may(sender, "kremlin." + node + (others ? ".others" : ""),
                "essentials." + node + (others ? ".others" : ""))) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return null;
        }
        if (!others) {
            if (sender instanceof Player p) return p;
            sender.sendMessage(plugin.msg("console-needs-player"));
            return null;
        }
        final Player target = Names.resolve(plugin.getServer(), args[at]);
        if (target == null) {
            sender.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[at])));
        }
        return target;
    }

    /** Tell the actor, and tell the target too when it was done to them by somebody else. */
    private void announce(final CommandSender sender, final Player target,
                          final String key, final boolean self) {
        final Component name = target.displayName();
        sender.sendMessage(plugin.msg(self ? key : key + "-other", Placeholder.component("player", name)));
        if (!self) target.sendMessage(plugin.msg(key, Placeholder.component("player", name)));
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);
        final List<String> online = Names.online(plugin.getServer(), List.of());

        if (WEATHER.contains(cmd)) {
            return args.length == 1 ? Names.filter(args[0], List.of("sun", "storm", "thunder")) : List.of();
        }
        if (SPEED.contains(cmd)) {
            return switch (args.length) {
                case 1 -> Names.filter(args[0], List.of("walk", "fly"));
                case 2 -> Names.filter(args[1], List.of("1", "2", "5", "10"));
                case 3 -> Names.filter(args[2], online);
                default -> List.of();
            };
        }
        if (SUDO.contains(cmd)) {
            return args.length == 1 ? Names.filter(args[0], online) : List.of();
        }
        if (cmd.equals("gamemode") || cmd.equals("gm") || cmd.equals("egamemode")) {
            if (args.length == 1) {
                return Names.filter(args[0], new ArrayList<>(
                        List.of("survival", "creative", "adventure", "spectator")));
            }
            return args.length == 2 ? Names.filter(args[1], online) : List.of();
        }
        return args.length == 1 ? Names.filter(args[0], online) : List.of();
    }

    /** Only used so /seen and /whois can say the same thing about someone who has never joined. */
    static boolean known(final OfflinePlayer o) {
        return o.hasPlayedBefore() || o.isOnline();
    }
}
