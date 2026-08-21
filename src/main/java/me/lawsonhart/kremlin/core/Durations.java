package me.lawsonhart.kremlin.core;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durations as staff type them: {@code 30m}, {@code 2d4h}, {@code 1mo}, {@code perm}.
 *
 * The format is EssentialsX's, because that is what the muscle memory and the existing ban
 * reasons are written in. The one trap it carries is that {@code m} means minutes and
 * {@code mo} means months -- so the units have to be matched longest-first, or "1mo" reads as
 * one minute followed by a stray letter.
 */
public final class Durations {

    /** What {@link #millis} returns for "forever". */
    public static final long PERMANENT = -1L;
    /** What it returns for something it could not read at all. */
    public static final long INVALID = 0L;

    /** Longest-first, so "mo" is tried before "m". */
    private static final Pattern PART = Pattern.compile(
            "(\\d+)\\s*(mo|months?|y|years?|w|weeks?|d|days?|h|hours?|m|mins?|minutes?|s|secs?|seconds?)",
            Pattern.CASE_INSENSITIVE);

    private Durations() {
    }

    /**
     * Milliseconds, {@link #PERMANENT} for forever, or {@link #INVALID} when it reads as nothing.
     *
     * A trailing scrap that is not a unit makes the whole thing invalid rather than being
     * ignored: "10x" is far more likely to be a typo for something than a request for ten
     * of nothing, and silently banning for 0ms would be the worst reading of it.
     */
    public static long millis(final String text) {
        if (text == null) return INVALID;
        // Whitespace out first, so "2d 4h" reads the same as "2d4h" -- the parts are matched
        // back to back, and a gap between them would otherwise read as something unparseable.
        final String trimmed = text.trim().toLowerCase(Locale.ROOT).replaceAll("\s+", "");
        if (trimmed.isEmpty()) return INVALID;
        if (trimmed.equals("perm") || trimmed.equals("permanent") || trimmed.equals("forever")
                || trimmed.equals("never") || trimmed.equals("-1")) {
            return PERMANENT;
        }

        final Matcher m = PART.matcher(trimmed);
        long total = 0L;
        int consumed = 0;
        while (m.find()) {
            if (m.start() != consumed) return INVALID; // something unreadable in between
            consumed = m.end();
            final long amount;
            try {
                amount = Long.parseLong(m.group(1));
            } catch (final NumberFormatException ex) {
                return INVALID; // a number too big to be a duration is not a duration
            }
            final long unit = unitMillis(m.group(2));
            if (amount > Long.MAX_VALUE / Math.max(1L, unit)) return PERMANENT; // past forever is forever
            total += amount * unit;
        }
        // Trailing junk, or nothing matched at all.
        return consumed == trimmed.length() && total > 0 ? total : INVALID;
    }

    private static long unitMillis(final String unit) {
        final char first = unit.charAt(0);
        if (unit.startsWith("mo")) return TimeUnit.DAYS.toMillis(30);
        return switch (first) {
            case 'y' -> TimeUnit.DAYS.toMillis(365);
            case 'w' -> TimeUnit.DAYS.toMillis(7);
            case 'd' -> TimeUnit.DAYS.toMillis(1);
            case 'h' -> TimeUnit.HOURS.toMillis(1);
            case 'm' -> TimeUnit.MINUTES.toMillis(1);
            default -> TimeUnit.SECONDS.toMillis(1);
        };
    }

    /**
     * How long is left, written the way it would be typed. Only the two largest units that
     * apply, because "2d 4h" is what somebody needs to know and "2d 4h 13m 6s" is not.
     */
    public static String describe(final long millis) {
        if (millis == PERMANENT) return "forever";
        if (millis <= 0) return "0s";
        final long days = TimeUnit.MILLISECONDS.toDays(millis);
        final long hours = TimeUnit.MILLISECONDS.toHours(millis) % 24;
        final long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        final long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;

        if (days > 0) return hours > 0 ? days + "d " + hours + "h" : days + "d";
        if (hours > 0) return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        if (minutes > 0) return seconds > 0 ? minutes + "m " + seconds + "s" : minutes + "m";
        return seconds + "s";
    }

    /**
     * How much of a limit is left to spend, given what the sender is allowed.
     *
     * A cap of {@link #PERMANENT} (the config's -1) means no limit at all, which is why this
     * cannot just be a {@code Math.min}: -1 would win every comparison and cap everything at
     * "already expired".
     */
    public static boolean within(final long asked, final long cap) {
        if (cap == PERMANENT) return true;          // no limit configured
        if (asked == PERMANENT) return false;       // forever, against a real limit
        return asked <= cap;
    }
}
