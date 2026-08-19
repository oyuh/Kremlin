package me.lawsonhart.kremlin;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tab completion for every command Kremlin takes over.
 *
 * It has to live here rather than in a TabCompleter for the same reason the commands themselves
 * are caught in PlayerCommandPreprocessEvent: a TabCompleter only runs for whoever won the label
 * at startup, so /tpa or /ec completing through Essentials would still be listing plain names.
 * AsyncTabCompleteEvent is the tab-completion twin of that choke point -- it sees the raw buffer
 * before anyone's completer, and setHandled(true) stops Essentials appending its own answer.
 *
 * Every suggestion is one you can actually act on. An online player is offered under their
 * nickname when they have one, because that is the name staff can see on the screen and typing it
 * back resolves through {@link Tpa#resolve}. Offline players are offered by real name, and only
 * the ones the command in hand can really reach: /invsee lists who we hold a snapshot for,
 * /adminhome lists who has homes stored. Suggesting a name the command would then refuse is worse
 * than suggesting nothing.
 */
public final class TabComplete implements Listener {

    /** Enough to be useful, few enough that the client isn't sent a novel on every keystroke. */
    private static final int LIMIT = 60;

    private final Combat plugin;

    TabComplete(Combat plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTabComplete(AsyncTabCompleteEvent e) {
        if (!e.isCommand() || !(e.getSender() instanceof Player p)) return;

        // -1 keeps the trailing empty token, so "/invsee " is a request to list everyone.
        String[] parts = e.getBuffer().split("\\s+", -1);
        if (parts.length != 2) return; // only the first argument ever names a player or a home
        String cmd = Combat.rootCommand(parts[0]);
        String token = parts[1];

        List<String> options = optionsFor(p, cmd);
        if (options == null) return; // not ours -- leave whoever owns the label to it

        e.setCompletions(filter(token, options));
        e.setHandled(true);
    }

    /**
     * Matches the way {@link Tpa#pickPlayer} does, on the stripped form as well as literally, so a
     * nickname carrying Essentials' prefix ("~Dalton") is still reached by typing the name. What
     * gets offered is the nickname as written -- prefix and all -- because that is what resolves.
     */
    static List<String> filter(String token, List<String> options) {
        String prefix = token.toLowerCase(Locale.ROOT);
        String bare = Tpa.bare(token);
        Set<String> matches = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String option : options) {
            if (option == null || option.isBlank()) continue;
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix) || Tpa.bare(option).startsWith(bare)) {
                matches.add(option);
            }
            if (matches.size() >= LIMIT) break;
        }
        return new ArrayList<>(matches);
    }

    /** What this command's first argument can be, or null if the command isn't ours. */
    private List<String> optionsFor(Player p, String cmd) {
        if (InvSee.INVSEE.contains(cmd)) {
            return may(p, "kremlin.invsee", "essentials.invsee")
                    ? withOnline(plugin.getInvSee().knownNames()) : null;
        }
        if (InvSee.ENDER.contains(cmd)) {
            return may(p, "kremlin.enderchest.others", "essentials.enderchest.others")
                    ? withOnline(plugin.getInvSee().knownNames()) : null;
        }
        if (Homes.ADMIN.contains(cmd)) {
            return may(p, "kremlin.home.others", "combatprev.home.others", "essentials.home.others")
                    ? withOnline(plugin.getHomes().knownNames()) : null;
        }
        // A teleport request only ever goes to somebody who is on right now.
        if (Tpa.ASK.contains(cmd) || Tpa.ASK_HERE.contains(cmd) || Tpa.ACCEPT.contains(cmd)
                || Tpa.DENY.contains(cmd) || Tpa.CANCEL.contains(cmd)) {
            List<String> online = withOnline(List.of());
            online.remove(Tpa.plainName(p));
            return online;
        }
        // Your own homes, for /home, /delhome and /renamehome.
        if (Homes.GO.contains(cmd) || Homes.DEL.contains(cmd) || Homes.RENAME.contains(cmd)) {
            return plugin.getHomes().homeNamesOf(p.getUniqueId());
        }
        return null;
    }

    /**
     * Everyone online under the name staff actually see -- their nickname where they have one,
     * their real name otherwise -- followed by the offline names this command can reach.
     */
    private List<String> withOnline(Iterable<String> offline) {
        List<String> out = new ArrayList<>();
        for (Player other : plugin.getServer().getOnlinePlayers()) out.add(Tpa.plainName(other));
        for (String name : offline) out.add(name);
        return out;
    }

    private static boolean may(Player p, String... nodes) {
        for (String node : nodes) {
            if (p.hasPermission(node)) return true;
        }
        return false;
    }
}
