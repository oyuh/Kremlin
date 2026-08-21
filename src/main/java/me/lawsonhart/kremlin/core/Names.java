package me.lawsonhart.kremlin.core;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Turning what somebody typed into the player they meant, and the tab-completion that offers
 * those names in the first place.
 *
 * This lived in Tpa and in the old AsyncTabCompleteEvent listener. Both are commands' business
 * rather than teleporting's -- {@link #plainName} was already being read from the player list
 * and {@link #resolve} from /invsee and /adminhome -- so it sits here, where every command can
 * reach it without depending on another feature.
 */
public final class Names {

    /** Enough to be useful, few enough that the client isn't sent a novel on every keystroke. */
    private static final int LIMIT = 60;

    private Names() {
    }

    /** Display name with formatting stripped, falling back to the real name. */
    public static String plainName(final Player p) {
        final String shown = PlainTextComponentSerializer.plainText().serialize(p.displayName()).trim();
        return shown.isEmpty() ? p.getName() : shown;
    }

    /** Nickname prefixes ("~Bob") and stray punctuation shouldn't stop a match. */
    public static String bare(final String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    }

    /**
     * Index of the player {@code query} refers to, or -1. Real name beats nickname, exact
     * beats prefix -- so "Bob" always finds the actual Bob even if someone is nicknamed "Bobby".
     */
    public static int pickPlayer(final String query, final List<String> names, final List<String> nicks) {
        if (query == null || query.isBlank()) return -1;
        final int n = Math.min(names.size(), nicks.size());
        final String q = query.toLowerCase(Locale.ROOT);
        final String qb = bare(query);
        if (qb.isEmpty()) return -1;

        for (int i = 0; i < n; i++) if (names.get(i).toLowerCase(Locale.ROOT).equals(q)) return i;
        for (int i = 0; i < n; i++) if (bare(nicks.get(i)).equals(qb)) return i;
        for (int i = 0; i < n; i++) if (names.get(i).toLowerCase(Locale.ROOT).startsWith(q)) return i;
        for (int i = 0; i < n; i++) if (bare(nicks.get(i)).startsWith(qb)) return i;
        return -1;
    }

    /**
     * A nickname lands in the player's display name, so matching that covers nicknames without
     * caring who set them. Real names are still tried first, so nobody can hide behind a
     * nickname that collides with someone else's actual name.
     */
    public static Player resolve(final Server server, final String query) {
        final List<Player> online = new ArrayList<>(server.getOnlinePlayers());
        final List<String> names = online.stream().map(Player::getName).toList();
        final List<String> nicks = online.stream().map(Names::plainName).toList();
        final int i = pickPlayer(query, names, nicks);
        return i < 0 ? null : online.get(i);
    }

    /**
     * The same lookup, widened to people who are not on right now: whoever is online under that
     * name or nickname, and failing that whoever the server has cached under it. Null when the
     * name means nobody -- callers say so rather than inventing a UUID for a typo, which is what
     * the deprecated name-based lookup would hand back.
     */
    public static UUID offlineId(final Server server, final String query) {
        final Player online = resolve(server, query);
        if (online != null) return online.getUniqueId();
        final OfflinePlayer cached = server.getOfflinePlayerIfCached(query);
        return cached == null ? null : cached.getUniqueId();
    }

    /**
     * Matches the way {@link #pickPlayer} does, on the stripped form as well as literally, so a
     * nickname carrying a prefix ("~Dalton") is still reached by typing the name. What gets
     * offered is the nickname as written -- prefix and all -- because that is what resolves.
     */
    public static List<String> filter(final String token, final List<String> options) {
        final String prefix = token.toLowerCase(Locale.ROOT);
        final String bare = bare(token);
        final Set<String> matches = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (final String option : options) {
            if (option == null || option.isBlank()) continue;
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix) || bare(option).startsWith(bare)) {
                matches.add(option);
            }
            if (matches.size() >= LIMIT) break;
        }
        return new ArrayList<>(matches);
    }

    /**
     * Every name the server has ever seen, cached briefly.
     *
     * getOfflinePlayers() builds a fresh array out of the user cache on every call, and tab
     * completion asks once per keystroke -- on a server with a few thousand past players that is
     * a lot of allocation to answer one letter. A minute-old list is more than fresh enough for
     * a name that, by definition, belongs to somebody who is not here.
     */
    private static volatile List<String> offlineNames = List.of();
    private static volatile long offlineNamesAt;
    private static final long OFFLINE_TTL_MILLIS = 60_000L;

    public static List<String> offlineNames(final Server server) {
        final long now = System.currentTimeMillis();
        if (now - offlineNamesAt < OFFLINE_TTL_MILLIS) return offlineNames;
        final List<String> names = new ArrayList<>();
        for (final OfflinePlayer offline : server.getOfflinePlayers()) {
            if (offline.getName() != null) names.add(offline.getName());
        }
        offlineNames = List.copyOf(names);
        offlineNamesAt = now;
        return offlineNames;
    }

    /**
     * Everyone online under the name staff actually see -- their nickname where they have one,
     * their real name otherwise -- followed by whichever offline names the caller can reach.
     * Suggesting a name the command would then refuse is worse than suggesting nothing.
     */
    public static List<String> online(final Server server, final Iterable<String> offline) {
        final List<String> out = new ArrayList<>();
        for (final Player other : server.getOnlinePlayers()) out.add(plainName(other));
        for (final String name : offline) out.add(name);
        return out;
    }
}
