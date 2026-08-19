package me.lawsonhart.kremlin;

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
final class TeamHook {

    private final Logger log;
    private ClassLoader loader;
    private Class<?> api;
    private Method getHomeOf;
    private Method teleportHome;
    private Method teamOf;
    private boolean warned;

    TeamHook(Plugin plugin) {
        this.log = plugin.getLogger();
        Plugin st = Bukkit.getPluginManager().getPlugin("SimpleTeams");
        if (st == null) {
            log.info("SimpleTeams not installed -- nothing to gate.");
            return;
        }
        loader = st.getClass().getClassLoader();
        try {
            api = Class.forName("net.godlycow.org.api.SimpleTeamsAPI", false, loader);
            // Only what the homes menu needs: is there a home to show, and run their /team home.
            getHomeOf = api.getMethod("getHomeOf", UUID.class);
            teleportHome = api.getMethod("teleportHome", Player.class, boolean.class);
            // Only the player list wants this one, and it isn't the method we verified against
            // 2.2.0 -- so it's probed rather than required, and its absence costs a lore line.
            teamOf = probe(api, "getTeamName", "getTeamOf", "getTeam", "teamOf");
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
    String teamNameOf(UUID player) {
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

    /** The team home of whichever team this player is on, or null. Read only -- for the menu. */
    Location homeOf(UUID player) {
        if (api == null) return null;
        try {
            return (Location) getHomeOf.invoke(null, player);
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    /**
     * Runs SimpleTeams' own team-home teleport, exactly as /team home would.
     *
     * fireEvent stays true on purpose: that is what puts it back through
     * TeamHomeTeleportEvent, so the combat block and warmup apply to the menu button too.
     * Nothing about team homes is decided here.
     */
    void teleportHome(Player player) {
        if (api == null) return;
        try {
            teleportHome.invoke(null, player, true);
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    /**
     * Puts SimpleTeams' own /team home under our teleport policy.
     *
     * TeamHomeTeleportEvent is the seam that plugin documents for exactly this -- it deliberately
     * has no combat check, warmup or cooldown of its own, and cancelling suppresses both its
     * teleport and its "teleporting" message on the understanding that we explain instead.
     *
     * Without this, /team home (and its /teamhq shortcut) reached the player through
     * teleportAsync, which our PlayerTeleportEvent listener only slows down when a flee penalty
     * applies -- so it skipped the base warmup that /hq has always had.
     */
    void onTeamHomeTeleport(Plugin plugin, BiConsumer<Player, Location> handler) {
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
