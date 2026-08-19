package me.lawsonhart.kremlin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who hurt the dragon most, for handing out the egg.
 *
 * Listens to EntityDamageEvent rather than EntityDamageByEntityEvent so nothing is missed: melee,
 * arrows, potions and explosions all arrive here, and DamageSource#getCausingEntity resolves the
 * shooter or thrower behind an indirect hit without pattern-matching projectiles by hand.
 *
 * Totals are kept per dragon, so two ends, or a re-summon, never bleed into each other. Finished
 * standings go to the server log as well as chat, so the numbers outlive a restart even though
 * the running tally does not.
 */
public final class DragonDamage implements Listener, CommandExecutor {

    /** How long after a bed pops its damage can still be blamed on whoever clicked it. */
    private static final long BED_BLAME_MILLIS = 500L;
    /** And how far from where they clicked. A bed blast reaches nothing like this far. */
    private static final double BED_BLAME_RANGE_SQ = 16.0 * 16.0;

    /** dragon uuid -> (player uuid -> damage dealt) */
    private final Map<UUID, Map<UUID, Double>> fights = new ConcurrentHashMap<>();
    /** last known name per uuid, so the table still reads properly for someone who logged off */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    private volatile Standings last;
    private volatile BedBlast lastBed;

    /** One player and their share of a fight. */
    record Score(UUID player, String name, double damage) {}

    /** A fight, ranked highest first. */
    record Standings(List<Score> scores, double total, long endedAt) {}

    /** Who set off a bed or anchor, and where. */
    private record BedBlast(UUID player, String name, Location where, long at) {}

    // ---------------------------------------------------------------- tracking

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDamaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof EnderDragon dragon)) return;

        UUID attacker = blame(e.getDamageSource());
        if (attacker == null) return;

        // getFinalDamage, not getDamage: what actually landed, after every modifier.
        fights.computeIfAbsent(dragon.getUniqueId(), k -> new ConcurrentHashMap<>())
                .merge(attacker, e.getFinalDamage(), Double::sum);
    }

    /** The player behind this damage, however indirect, or null if nobody. */
    private UUID blame(DamageSource source) {
        Entity causing = source.getCausingEntity();
        if (causing instanceof Player p) {
            names.put(p.getUniqueId(), p.getName());
            return p.getUniqueId();
        }
        // Beds and anchors explode with no entity attached: vanilla builds that damage source
        // from a position, not a player. Remembering who just clicked one is the only way to
        // credit the bed damage that most dragon fights are actually won with.
        // ponytail: time-and-distance guess. Two players popping beds in the same spot within
        // half a second could cross wires; exact attribution would need NMS.
        if (!DamageType.BAD_RESPAWN_POINT.equals(source.getDamageType())) return null;
        BedBlast bed = lastBed;
        if (bed == null || System.currentTimeMillis() - bed.at() > BED_BLAME_MILLIS) return null;
        Location hit = source.getDamageLocation();
        if (hit != null && (!hit.getWorld().equals(bed.where().getWorld())
                || hit.distanceSquared(bed.where()) > BED_BLAME_RANGE_SQ)) {
            return null;
        }
        names.put(bed.player(), bed.name());
        return bed.player();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedClicked(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Material type = e.getClickedBlock().getType();
        if (!Tag.BEDS.isTagged(type) && type != Material.RESPAWN_ANCHOR) return;
        lastBed = new BedBlast(e.getPlayer().getUniqueId(), e.getPlayer().getName(),
                e.getClickedBlock().getLocation(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragonDied(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof EnderDragon dragon)) return;
        Map<UUID, Double> tally = fights.remove(dragon.getUniqueId());
        if (tally == null || tally.isEmpty()) return;

        Standings standings = rank(tally);
        this.last = standings;
        Score top = standings.scores().get(0);

        Bukkit.broadcast(Component.text("The dragon is down. ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text("Top damage: ", NamedTextColor.GRAY))
                .append(name(top))
                .append(Component.text(" with " + round(top.damage())
                        + " (" + percent(top.damage(), standings.total()) + ")", NamedTextColor.YELLOW)));
        Bukkit.broadcast(Component.text("Full breakdown: /dragondamage", NamedTextColor.DARK_GRAY));

        // The server log is the copy that outlives a restart.
        StringBuilder line = new StringBuilder("Dragon damage, total " + round(standings.total()) + ":");
        for (Score score : standings.scores()) {
            line.append(" ").append(score.name()).append("=").append(round(score.damage()));
        }
        Bukkit.getLogger().info("[Kremlin] " + line);
    }

    private Standings rank(Map<UUID, Double> tally) {
        List<Score> scores = new ArrayList<>();
        double total = 0.0;
        for (Map.Entry<UUID, Double> entry : tally.entrySet()) {
            scores.add(new Score(entry.getKey(), names.getOrDefault(entry.getKey(), "unknown"), entry.getValue()));
            total += entry.getValue();
        }
        scores.sort(Comparator.comparingDouble(Score::damage).reversed());
        return new Standings(List.copyOf(scores), total, System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // A fight in progress wins: mid-fight is when people actually watch this table.
        Map<UUID, Double> live = fights.values().stream()
                .filter(m -> !m.isEmpty()).findFirst().orElse(null);
        Standings standings = live != null ? rank(live) : this.last;

        if (standings == null || standings.scores().isEmpty()) {
            sender.sendMessage(Component.text("No dragon damage recorded yet.", NamedTextColor.GRAY));
            return true;
        }

        sender.sendMessage(Component.text(live != null
                ? "Dragon damage so far - total " + round(standings.total())
                : "Last dragon fight - total " + round(standings.total()), NamedTextColor.LIGHT_PURPLE));
        int place = 1;
        for (Score score : standings.scores()) {
            sender.sendMessage(Component.text(" " + place++ + ". ", NamedTextColor.DARK_GRAY)
                    .append(name(score))
                    .append(Component.text(" - " + round(score.damage())
                            + " (" + percent(score.damage(), standings.total()) + ")", NamedTextColor.GRAY)));
        }
        return true;
    }

    /** Their display name while they are online, so nicknames and prefixes show. */
    private static Component name(Score score) {
        Player online = Bukkit.getPlayer(score.player());
        return online != null ? online.displayName() : Component.text(score.name(), NamedTextColor.WHITE);
    }

    static String round(double damage) {
        return String.format(Locale.ROOT, "%.1f", damage);
    }

    static String percent(double part, double total) {
        return total <= 0 ? "0%" : String.format(Locale.ROOT, "%.1f%%", part / total * 100.0);
    }
}
