package me.lawsonhart.kremlin.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * The one seam between Kremlin and SimpleTeams.
 *
 * SimpleTeams owns team homes outright -- /team home, /team sethome, /team delhome and the /hq,
 * /sethq and /delhq shortcuts it registers itself, along with the rank rules, permissions and
 * team broadcasts that come with them. Kremlin used to reimplement all of that; now it only
 * decides whether the teleport may happen, which is what a combat plugin is for.
 *
 * Reflective because SimpleTeams still isn't on a Maven repo. If it's missing, or the event
 * moves, Kremlin logs it and carries on -- everything else works standalone.
 *
 * Verified against net.godlycow.org.event.TeamHomeTeleportEvent in SimpleTeams 2.2.0.
 */
public final class TeamHook {

    private final Logger log;
    private ClassLoader loader;
    private Class<?> api;
    private Method getHomeOf;
    private Method homeEnabled;
    private Method teamOf;
    private Method byName;
    private boolean warned;

    public TeamHook(Plugin plugin) {
        this.log = plugin.getLogger();
        Plugin st = Bukkit.getPluginManager().getPlugin("SimpleTeams");
        if (st == null) {
            log.info("SimpleTeams not installed -- nothing to gate.");
            return;
        }
        loader = st.getClass().getClassLoader();
        try {
            api = Class.forName("net.godlycow.org.api.SimpleTeamsAPI", false, loader);
            // Only what we need to answer /hq ourselves: is the feature on, and where is it.
            getHomeOf = api.getMethod("getHomeOf", UUID.class);
            homeEnabled = api.getMethod("isHomeEnabled");
            // Only the player list wants this one, and it isn't the method we verified against
            // 2.2.0 -- so it's probed rather than required, and its absence costs a lore line.
            teamOf = probe(api, "getTeamName", "getTeamOf", "getTeam", "teamOf");
            // Only used to tell an admin they typed a team name that does not exist, so its
            // absence costs a warning rather than the command.
            try {
                byName = api.getMethod("getTeamByName", String.class);
            } catch (NoSuchMethodException ignored) {
                byName = null;
            }
            if (teamOf == null) {
                log.info("SimpleTeams has no team lookup we recognise -- /playerlist will show no teams.");
            }
            log.info("Found SimpleTeams " + st.getPluginMeta().getVersion() + ".");
        } catch (Throwable t) {
            api = null;
            log.warning("SimpleTeams is installed but its API didn't match (" + t + ").");
            log.warning("The team HQ button is hidden; /team home itself still works.");
        }
    }

    private static Method probe(Class<?> api, String... names) {
        for (String name : names) {
            try {
                return api.getMethod(name, UUID.class);
            } catch (NoSuchMethodException ignored) {
                // Next candidate. None matching is a supported outcome, not a failure.
            }
        }
        return null;
    }

    /**
     * The name of this player's team, or null for teamless (and for any SimpleTeams that doesn't
     * expose the lookup). Accepts either a plain String or a team object with a getName().
     */
    /**
     * Whether team membership can be answered at all right now.
     *
     * The difference between "not in a team" and "cannot tell" matters: a caller that removes a
     * team role on a null answer would strip everybody's role the moment SimpleTeams is missing.
     */
    public boolean available() {
        return api != null && teamOf != null;
    }

    public String teamNameOf(UUID player) {
        if (api == null || teamOf == null) return null;
        try {
            Object team = teamOf.invoke(null, player);
            if (team == null) return null;
            if (team instanceof String name) return name.isBlank() ? null : name;
            Object name = team.getClass().getMethod("getName").invoke(team);
            return name == null || name.toString().isBlank() ? null : name.toString();
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    /** False when SimpleTeams is missing or has team homes switched off in its config. */
    /**
     * Whether a team of this name exists right now.
     *
     * Answers true when we cannot tell -- SimpleTeams missing, or the lookup moved -- because
     * this only ever gates a warning, and a false negative would refuse a perfectly good name.
     */
    public boolean teamExists(String name) {
        if (api == null || byName == null || name == null || name.isBlank()) return true;
        try {
            Object found = byName.invoke(null, name);
            return !(found instanceof java.util.Optional<?> opt) || opt.isPresent();
        } catch (Throwable t) {
            warnOnce(t);
            return true;
        }
    }

    public boolean homeEnabled() {
        if (api == null || homeEnabled == null) return false;
        try {
            return (Boolean) homeEnabled.invoke(null);
        } catch (Throwable t) {
            warnOnce(t);
            return false;
        }
    }

    /** The team home of whichever team this player is on, or null. */
    public Location homeOf(UUID player) {
        if (api == null) return null;
        try {
            return (Location) getHomeOf.invoke(null, player);
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    /**
     * The backstop for anything else that asks SimpleTeams to send a player to their team home.
     *
     * The commands themselves ({@code /hq}, {@code /team home} and friends) are taken in
     * {@link Homes}' PlayerCommandPreprocessEvent instead, because this event only ever reaches
     * us if it is fired at all -- and when it isn't, the teleport happens with no warmup at all,
     * which is exactly the bug this replaced. A cancelled command never reaches SimpleTeams, so
     * the two paths cannot both run for one teleport.
     */
    public void onTeamHomeTeleport(Plugin plugin, BiConsumer<Player, Location> handler) {
        if (loader == null) return;
        try {
            Class<? extends Event> eventClass = Class
                    .forName("net.godlycow.org.event.TeamHomeTeleportEvent", false, loader)
                    .asSubclass(Event.class);
            Method eventPlayer = eventClass.getMethod("getPlayer");
            Method eventDestination = eventClass.getMethod("getDestination");
            Method eventCancelled = eventClass.getMethod("setCancelled", boolean.class);

            Bukkit.getPluginManager().registerEvent(eventClass, new Listener() {
            }, EventPriority.NORMAL, (listener, event) -> {
                if (!eventClass.isInstance(event)) return;
                try {
                    // Always cancel: whether we refuse it or merely delay it, the teleport is
                    // ours to finish from here.
                    eventCancelled.invoke(event, true);
                    Player player = (Player) eventPlayer.invoke(event);
                    Location destination = ((Location) eventDestination.invoke(event)).clone();
                    handler.accept(player, destination);
                } catch (Throwable t) {
                    warnOnce(t);
                }
            }, plugin);
            log.info("/team home now runs through the combat check and teleport warmup.");
        } catch (Throwable t) {
            log.warning("SimpleTeams has no usable TeamHomeTeleportEvent (" + t + ").");
            log.warning("/team home still gets the combat block and flee penalty via PlayerTeleportEvent,"
                    + " but not the base warmup.");
        }
    }

    private void warnOnce(Throwable t) {
        if (warned) return;
        warned = true;
        log.warning("SimpleTeams lookup failed, treating everyone as teamless: " + t);
    }
}
