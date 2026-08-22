package me.lawsonhart.kremlin.misc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import me.lawsonhart.kremlin.core.Format;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;

import io.papermc.paper.registry.RegistryAccess;

/**
 * /spawnmob -- any spawnable entity, with any of the knobs its own API exposes.
 *
 * The per-mob options are not written out by hand: an entity's variety lives in the setters on
 * its Bukkit interface ({@code Horse#setColor}, {@code Wolf#setVariant}, {@code Slime#setSize}),
 * so those are reflected off {@link EntityType#getEntityClass()} and offered as {@code key:value}
 * arguments. That is one screen of code instead of a branch per mob, and a mob added by a future
 * Minecraft version brings its own options along with no change here.
 *
 * Deprecated setters are skipped -- that is what keeps {@code setCustomName(String)} out of the
 * way of the {@code name:} key -- as is most of {@link Entity} itself, which is full of setters
 * (velocity, ticks lived, portal cooldown) that nobody wants to see in a completion list.
 */
public final class SpawnMobCommand implements CommandExecutor, TabCompleter {

    /** Enough to be silly with, not enough to wedge the server on a typo. */
    private static final int MAX_AMOUNT = 100;

    /** The keys handled here rather than by a setter on the mob. */
    private static final List<String> CORE =
            List.of("amount", "name", "at", "world", "maxhealth", "baby");

    /** The few {@link Entity} setters worth offering; the rest of that interface is noise. */
    private static final Set<String> ENTITY_OPTIONS = Set.of("glowing", "invulnerable", "silent",
            "gravity", "persistent", "customnamevisible", "visualfire", "fireticks", "freezeticks");

    /**
     * Setters the sweep finds but nobody wants to see: living-entity bookkeeping rather than any
     * part of what a mob is. Blacklisted by name because they arrive from half a dozen different
     * interfaces, and the alternative -- naming every interface worth reading -- is longer and
     * would drop each new one Mojang adds.
     */
    private static final Set<String> NOISE = Set.of("op", "killer", "lastdamage", "seed",
            "bodyyaw", "arrowcooldown", "arrowsinbody", "beestingercooldown", "beestingersinbody",
            "noactionticks", "activeitemremainingtime", "frictionstate", "riptiding", "jumping",
            "nodamageticks", "maximumnodamageticks", "absorptionamount", "remainingair",
            "maximumair", "lovemodeticks", "leashholder", "loottable");

    /** Reflection once per mob class, not once per keystroke. */
    private static final Map<Class<?>, Map<String, Method>> OPTIONS = new ConcurrentHashMap<>();

    private final Plugin plugin;

    public SpawnMobCommand(final Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!Perms.may(sender, "kremlin.spawnmob", "essentials.spawnmob")) {
            sender.sendMessage(red("You are not allowed to spawn mobs."));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(red("Usage: /" + label + " <mob> [amount] [key:value ...]"));
            return true;
        }

        final EntityType type = type(args[0]);
        if (type == null) {
            sender.sendMessage(red("There is no mob called " + args[0] + "."));
            return true;
        }
        final Map<String, Method> setters = options(type);

        // Parsed in full before anything is spawned, so a typo in the last argument does not
        // leave a herd of half-configured mobs behind.
        final Map<String, String> given = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            final int colon = args[i].indexOf(':');
            if (colon < 1) {
                // Essentials wrote the count as a bare second argument; keep that working.
                if (i == 1 && args[i].chars().allMatch(Character::isDigit)) {
                    given.put("amount", args[i]);
                    continue;
                }
                sender.sendMessage(red("Options are written key:value -- \"" + args[i] + "\" is not."));
                return true;
            }
            final String key = args[i].substring(0, colon).toLowerCase(Locale.ROOT);
            if (!CORE.contains(key) && !setters.containsKey(key)) {
                sender.sendMessage(red("A " + name(type) + " has no " + key + ". It has: "
                        + String.join(", ", keys(setters))));
                return true;
            }
            given.put(key, args[i].substring(colon + 1));
        }

        final int amount = number(given.get("amount"), 1);
        if (amount < 1 || amount > MAX_AMOUNT) {
            sender.sendMessage(red("Spawn between 1 and " + MAX_AMOUNT + " of them."));
            return true;
        }
        final Double maxHealth = given.containsKey("maxhealth")
                ? (Double) value(double.class, given.get("maxhealth"), sender.getServer()) : null;
        if (given.containsKey("maxhealth") && (maxHealth == null || maxHealth <= 0)) {
            sender.sendMessage(red("maxhealth must be a positive number."));
            return true;
        }
        final Boolean baby = given.containsKey("baby")
                ? (Boolean) value(boolean.class, given.get("baby"), sender.getServer()) : null;
        if (given.containsKey("baby") && baby == null) {
            sender.sendMessage(red("baby is true or false."));
            return true;
        }
        final Component customName = given.containsKey("name")
                ? Format.render(given.get("name").replace('_', ' ')) : null;

        final Location where = location(sender, given.get("at"), given.get("world"));
        if (where == null) return true;

        // Method -> the value to hand it, resolved now so failures are reported once and early.
        final Map<Method, Object> apply = new LinkedHashMap<>();
        for (final Map.Entry<String, String> option : given.entrySet()) {
            if (CORE.contains(option.getKey())) continue;
            final Method setter = setters.get(option.getKey());
            final Class<?> wants = setter.getParameterTypes()[0];
            final Object value = value(wants, option.getValue(), sender.getServer());
            if (value == null) {
                sender.sendMessage(red(option.getValue() + " is not a " + option.getKey()
                        + " a " + name(type) + " can have. Try: "
                        + String.join(", ", first(values(wants, sender.getServer()), 12))));
                return true;
            }
            apply.put(setter, value);
        }

        // Folia owns entities by region, so the spawn has to happen on the region that owns the
        // destination -- which is not necessarily the one running this command.
        Bukkit.getRegionScheduler().execute(plugin, where, () -> {
            final Set<String> failed = new LinkedHashSet<>();
            for (int i = 0; i < amount; i++) {
                where.getWorld().spawnEntity(where, type, CreatureSpawnEvent.SpawnReason.COMMAND, mob -> {
                    // Health first: setting it above the default maximum is refused otherwise.
                    if (maxHealth != null && mob instanceof LivingEntity living) {
                        final AttributeInstance health = living.getAttribute(Attribute.MAX_HEALTH);
                        if (health != null) {
                            health.setBaseValue(maxHealth);
                            living.setHealth(maxHealth);
                        }
                    }
                    // Ageable says baby with a no-argument call, so the setter sweep cannot see it.
                    if (baby != null && mob instanceof Ageable ageable) {
                        if (baby) ageable.setBaby();
                        else ageable.setAdult();
                    }
                    if (customName != null) {
                        mob.customName(customName);
                        mob.setCustomNameVisible(true);
                    }
                    apply.forEach((setter, value) -> {
                        try {
                            setter.invoke(mob, value);
                        } catch (final ReflectiveOperationException | RuntimeException e) {
                            failed.add(setter.getName().substring(3).toLowerCase(Locale.ROOT));
                        }
                    });
                });
            }
            sender.sendMessage(Component.text("Spawned ", NamedTextColor.GREEN)
                    .append(Component.text(amount + " " + name(type), NamedTextColor.GOLD))
                    .append(Component.text(" at " + where.getBlockX() + ", " + where.getBlockY()
                            + ", " + where.getBlockZ() + " in " + where.getWorld().getName() + ".",
                            NamedTextColor.GREEN)));
            if (!failed.isEmpty()) {
                sender.sendMessage(red("Could not apply: " + String.join(", ", failed)));
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String label, final String[] args) {
        if (!Perms.may(sender, "kremlin.spawnmob", "essentials.spawnmob")) return List.of();
        if (args.length <= 1) {
            return prefixed(args.length == 0 ? "" : args[0], spawnable());
        }
        final EntityType type = type(args[0]);
        if (type == null) return List.of();

        final String token = args[args.length - 1];
        final int colon = token.indexOf(':');
        if (colon < 0) {
            // Keys still going spare -- an option already given is not offered twice.
            final List<String> used = new ArrayList<>();
            for (int i = 1; i < args.length - 1; i++) {
                final int at = args[i].indexOf(':');
                if (at > 0) used.add(args[i].substring(0, at).toLowerCase(Locale.ROOT));
            }
            final List<String> keys = new ArrayList<>(CORE);
            keys.addAll(keys(options(type)));
            keys.removeAll(used);
            return prefixed(token, keys.stream().map(k -> k + ":").toList());
        }

        final String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
        final Method setter = options(type).get(key);
        final List<String> values = setter != null
                ? values(setter.getParameterTypes()[0], sender.getServer())
                : core(key, sender);
        return prefixed(token, values.stream().map(v -> key + ":" + v).toList());
    }

    // -- options ------------------------------------------------------------------------------

    private static Map<String, Method> options(final EntityType type) {
        final Class<?> mob = type.getEntityClass();
        return mob == null ? Map.of() : OPTIONS.computeIfAbsent(mob, SpawnMobCommand::discover);
    }

    /** Every single-argument setter on the mob's interface that takes a value we can spell. */
    static Map<String, Method> discover(final Class<?> mob) {
        final Map<String, Method> found = new TreeMap<>();
        for (final Method method : mob.getMethods()) {
            if (method.getParameterCount() != 1 || method.isSynthetic() || method.isBridge()) continue;
            if (!method.getName().startsWith("set") || method.getName().length() == 3) continue;
            if (method.isAnnotationPresent(Deprecated.class)) continue;
            if (!settable(method.getParameterTypes()[0])) continue;
            final String key = method.getName().substring(3).toLowerCase(Locale.ROOT);
            if (method.getDeclaringClass() == Entity.class && !ENTITY_OPTIONS.contains(key)) continue;
            if (CORE.contains(key) || NOISE.contains(key)) continue;
            found.putIfAbsent(key, method);
        }
        return Collections.unmodifiableMap(found);
    }

    /** Numbers, flags, enums, registry types (wolf variants, villager professions) and players. */
    private static boolean settable(final Class<?> wants) {
        if (wants.isPrimitive()) {
            return wants == boolean.class || wants == int.class || wants == long.class
                    || wants == float.class || wants == double.class;
        }
        return wants.isEnum() || Keyed.class.isAssignableFrom(wants)
                || (wants != Object.class && wants.isAssignableFrom(Player.class));
    }

    /** The typed value, or null when the text does not name one. */
    private static Object value(final Class<?> wants, final String text, final Server server) {
        try {
            if (wants == boolean.class) {
                return "true".equalsIgnoreCase(text) ? Boolean.TRUE
                        : "false".equalsIgnoreCase(text) ? Boolean.FALSE : null;
            }
            if (wants == int.class) return Integer.valueOf(text);
            if (wants == long.class) return Long.valueOf(text);
            if (wants == float.class) return Float.valueOf(text);
            if (wants == double.class) return Double.valueOf(text);
            if (wants.isEnum()) {
                for (final Object constant : wants.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equalsIgnoreCase(text)) return constant;
                }
                return null;
            }
            if (Keyed.class.isAssignableFrom(wants)) {
                final Registry<? extends Keyed> registry = registry(wants);
                final NamespacedKey key = NamespacedKey.fromString(text.toLowerCase(Locale.ROOT));
                return registry == null || key == null ? null : registry.get(key);
            }
            return Names.resolve(server, text);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /** What may follow the colon, for completion. Numbers get a couple of suggestions, not a list. */
    private static List<String> values(final Class<?> wants, final Server server) {
        if (wants == boolean.class) return List.of("true", "false");
        if (wants.isPrimitive()) return List.of("1", "5", "10");
        if (wants.isEnum()) {
            final List<String> names = new ArrayList<>();
            for (final Object constant : wants.getEnumConstants()) {
                names.add(((Enum<?>) constant).name().toLowerCase(Locale.ROOT));
            }
            return names;
        }
        if (Keyed.class.isAssignableFrom(wants)) {
            final Registry<? extends Keyed> registry = registry(wants);
            return registry == null ? List.of()
                    : registry.keyStream().map(NamespacedKey::value).sorted().toList();
        }
        return server.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    /** Values for the keys this class handles itself. */
    private static List<String> core(final String key, final CommandSender sender) {
        return switch (key) {
            case "amount" -> List.of("1", "5", "10", "50");
            case "maxhealth" -> List.of("20", "100", "1024");
            case "baby" -> List.of("true", "false");
            case "world" -> Bukkit.getWorlds().stream().map(World::getName).toList();
            case "at" -> {
                final List<String> at = new ArrayList<>(List.of("~,~,~"));
                if (sender instanceof Player p) {
                    at.add(p.getLocation().getBlockX() + "," + p.getLocation().getBlockY()
                            + "," + p.getLocation().getBlockZ());
                }
                Bukkit.getOnlinePlayers().forEach(p -> at.add(p.getName()));
                yield at;
            }
            default -> List.of();
        };
    }

    /**
     * Registry-backed types (Wolf.Variant, Villager.Profession, Cat.Type) no longer being enums
     * is why this exists; a Keyed type with no registry behind it simply offers nothing.
     */
    @SuppressWarnings({"deprecation", "removal"}) // The only lookup that starts from the class.
    private static Registry<? extends Keyed> registry(final Class<?> wants) {
        try {
            return RegistryAccess.registryAccess().getRegistry(wants.asSubclass(Keyed.class));
        } catch (final RuntimeException e) {
            return null;
        }
    }

    // -- where ---------------------------------------------------------------------------------

    /** The spawn point, or null after telling the sender why there isn't one. */
    private static Location location(final CommandSender sender, final String at, final String world) {
        final Location base = sender instanceof Player player ? player.getLocation() : null;

        if (at != null && !at.contains(",")) {
            final Player target = Names.resolve(sender.getServer(), at);
            if (target == null) {
                sender.sendMessage(red(at + " is not online, and is not an x,y,z either."));
                return null;
            }
            return target.getLocation();
        }

        World in = base == null ? null : base.getWorld();
        if (world != null) {
            in = Bukkit.getWorld(world);
            if (in == null) {
                sender.sendMessage(red("There is no world called " + world + "."));
                return null;
            }
        }
        if (at == null) {
            if (base == null) {
                sender.sendMessage(red("From the console, say where: at:<x,y,z> or at:<player>."));
                return null;
            }
            return in == base.getWorld() ? base : new Location(in, base.getX(), base.getY(), base.getZ());
        }

        final String[] parts = at.split(",");
        if (parts.length != 3 || in == null) {
            sender.sendMessage(red("A location is at:<x,y,z>, and needs world:<name> from the console."));
            return null;
        }
        final double[] xyz = new double[3];
        for (int i = 0; i < 3; i++) {
            final Double value = coordinate(parts[i].trim(), base, i);
            if (value == null) {
                sender.sendMessage(red(parts[i] + " is not a coordinate."));
                return null;
            }
            xyz[i] = value;
        }
        return new Location(in, xyz[0], xyz[1], xyz[2]);
    }

    /** A number, or {@code ~} and {@code ~n} relative to where the sender is standing. */
    static Double coordinate(final String text, final Location base, final int axis) {
        try {
            if (!text.startsWith("~")) return Double.valueOf(text);
            if (base == null) return null;
            final double origin = axis == 0 ? base.getX() : axis == 1 ? base.getY() : base.getZ();
            return origin + (text.length() == 1 ? 0 : Double.parseDouble(text.substring(1)));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    // -- odds and ends -------------------------------------------------------------------------

    private static EntityType type(final String text) {
        final String wanted = text.toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
        for (final EntityType type : EntityType.values()) {
            if (spawnable(type) && name(type).equals(wanted)) return type;
        }
        return null;
    }

    private static boolean spawnable(final EntityType type) {
        return type.isSpawnable() && type.getEntityClass() != null && type != EntityType.PLAYER;
    }

    private static List<String> spawnable() {
        final List<String> names = new ArrayList<>();
        for (final EntityType type : EntityType.values()) {
            if (spawnable(type)) names.add(name(type));
        }
        Collections.sort(names);
        return names;
    }

    private static String name(final EntityType type) {
        return type.getKey().value();
    }

    private static List<String> keys(final Map<String, Method> setters) {
        return List.copyOf(setters.keySet());
    }

    private static int number(final String text, final int fallback) {
        try {
            return text == null ? fallback : Integer.parseInt(text);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    private static List<String> prefixed(final String token, final List<String> options) {
        final String lower = token.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).limit(80).toList();
    }

    private static List<String> first(final List<String> values, final int limit) {
        return values.size() <= limit ? values : values.subList(0, limit);
    }

    private static Component red(final String text) {
        return Component.text(text, NamedTextColor.RED);
    }
}
