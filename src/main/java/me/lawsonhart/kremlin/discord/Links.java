package me.lawsonhart.kremlin.discord;

import me.lawsonhart.kremlin.core.Users;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is linked to whom, and the codes that get them there.
 *
 * The flow is symmetric on purpose: whichever side you start on hands you a code, and you finish
 * by typing it on the other side. That is one table of pending codes rather than two flows --
 * a code simply remembers which side made it, and may only be redeemed from the other.
 *
 * Everything here is deliberately free of Bukkit and JDA so it can be tested directly. The
 * store is {@link Users}, so a link sits beside every other per-player value.
 */
public final class Links {

    /** The users.yml key holding the Discord user id, as a string -- it does not fit an int. */
    public static final String KEY = "discord";

    /**
     * Unambiguous characters only: no O/0, I/1, S/5. A code is read off one screen and typed into
     * another, usually on a phone, and "was that a zero" is the whole cost of getting this wrong.
     */
    static final String ALPHABET = "ABCDEFGHJKLMNPQRTUVWXYZ23468";

    /** What happened, so the caller can say something useful. */
    public enum Result { LINKED, UNKNOWN_CODE, EXPIRED, WRONG_SIDE, ALREADY_LINKED, TARGET_TAKEN }

    /** A code waiting to be redeemed. Exactly one of the two ids is set. */
    record Pending(UUID player, long discordId, long expires) {

        boolean fromGame() {
            return player != null;
        }

        boolean expired(final long now) {
            return now >= expires;
        }
    }

    private final Users users;
    private final SecureRandom random = new SecureRandom();

    /** code -> who is waiting on it. Codes are short-lived, so this never grows. */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private long codeMillis = 300_000L;
    private int codeLength = 6;

    public Links(final Users users) {
        this.users = users;
    }

    public void configure(final long codeSeconds, final int length) {
        this.codeMillis = Math.max(30L, codeSeconds) * 1000L;
        this.codeLength = Math.max(4, Math.min(12, length));
    }

    // ---------------------------------------------------------------- the pairing

    /** Their Discord user id, or 0 when they are not linked. */
    public long discordOf(final UUID player) {
        final String stored = users.text(player, KEY);
        if (stored == null || stored.isBlank()) return 0L;
        try {
            return Long.parseLong(stored);
        } catch (final NumberFormatException ex) {
            return 0L;
        }
    }

    public boolean isLinked(final UUID player) {
        return discordOf(player) != 0L;
    }

    /**
     * The player behind a Discord id, or null.
     *
     * ponytail: a scan of everyone we hold a record for. Fine at this size and it cannot drift
     * out of step with the forward mapping; build a reverse index if it ever shows up in a profile.
     */
    public UUID playerOf(final long discordId) {
        if (discordId == 0L) return null;
        final String wanted = String.valueOf(discordId);
        for (final UUID id : users.ids()) {
            if (wanted.equals(users.text(id, KEY))) return id;
        }
        return null;
    }

    public void unlink(final UUID player) {
        users.set(player, KEY, null);
    }

    // ---------------------------------------------------------------- codes

    /** A code for a player to type in Discord. Any earlier code of theirs stops working. */
    public String startFromGame(final UUID player) {
        forget(p -> player.equals(p.player()));
        return issue(new Pending(player, 0L, System.currentTimeMillis() + codeMillis));
    }

    /** A code for a Discord user to type in game. Any earlier code of theirs stops working. */
    public String startFromDiscord(final long discordId) {
        forget(p -> p.discordId() == discordId);
        return issue(new Pending(null, discordId, System.currentTimeMillis() + codeMillis));
    }

    private String issue(final Pending waiting) {
        String code;
        do {
            code = generate();
        } while (pending.putIfAbsent(code, waiting) != null);
        sweep();
        return code;
    }

    private String generate() {
        final StringBuilder out = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    private void forget(final java.util.function.Predicate<Pending> which) {
        pending.values().removeIf(which);
    }

    private void sweep() {
        final long now = System.currentTimeMillis();
        pending.values().removeIf(p -> p.expired(now));
    }

    /** A player typing a code that was made in Discord. */
    public Result redeemInGame(final UUID player, final String code) {
        if (isLinked(player)) return Result.ALREADY_LINKED;
        return redeem(code, true, waiting -> finish(player, waiting.discordId()));
    }

    /** A Discord user typing a code that was made in game. */
    public Result redeemInDiscord(final long discordId, final String code) {
        if (playerOf(discordId) != null) return Result.ALREADY_LINKED;
        return redeem(code, false, waiting -> finish(waiting.player(), discordId));
    }

    /**
     * The shared half. {@code wantFromDiscord} is which side the code must have come from --
     * redeeming your own code would link you to yourself and prove nothing.
     */
    private Result redeem(final String code, final boolean wantFromDiscord,
                          final java.util.function.Function<Pending, Result> complete) {
        if (code == null || code.isBlank()) return Result.UNKNOWN_CODE;
        final String normalised = normalise(code);
        final Pending waiting = pending.get(normalised);
        if (waiting == null) return Result.UNKNOWN_CODE;
        if (waiting.expired(System.currentTimeMillis())) {
            pending.remove(normalised);
            return Result.EXPIRED;
        }
        if (waiting.fromGame() == wantFromDiscord) return Result.WRONG_SIDE;

        final Result result = complete.apply(waiting);
        // Single use, but only once it actually worked -- a refused redemption must not burn
        // the code somebody is still waiting to use.
        if (result == Result.LINKED) pending.remove(normalised);
        return result;
    }

    private Result finish(final UUID player, final long discordId) {
        if (player == null || discordId == 0L) return Result.UNKNOWN_CODE;
        if (isLinked(player)) return Result.ALREADY_LINKED;
        final UUID already = playerOf(discordId);
        if (already != null && !already.equals(player)) return Result.TARGET_TAKEN;
        users.set(player, KEY, String.valueOf(discordId));
        return Result.LINKED;
    }

    /**
     * Codes are shown upper case and typed however people type. Stripping the characters that are
     * not in the alphabet also absorbs the spaces and dashes people add reading one out.
     */
    static String normalise(final String typed) {
        return typed.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /** Only for the status line. */
    public int pendingCount() {
        sweep();
        return pending.size();
    }
}
