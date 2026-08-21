package me.lawsonhart.kremlin.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turning an EssentialsX-style format string into a component.
 *
 * The whole point of this class is that an existing EssentialsChat config keeps working
 * unchanged, so it speaks that config's dialect: {@code {PLACEHOLDER}} tokens and legacy
 * {@code &} colour codes. MiniMessage tags are understood too, by translating the legacy codes
 * into MiniMessage and then parsing the lot once -- a format may mix both.
 *
 * The player's own message is never pasted into that string. It is passed as a component through
 * the {@code <message>} tag instead, so no amount of markup typed in chat can escape into the
 * format. That is the one rule here that is about safety rather than compatibility.
 */
public final class Format {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Any {@code {TOKEN}}. Unknown ones are left alone for PlaceholderAPI to have a go at. */
    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    /** {@code &#RRGGBB}, the hex form EssentialsX writes, and {@code &a} style codes. */
    private static final Pattern HEX = Pattern.compile("[&§]#([0-9A-Fa-f]{6})");
    /** The serialised hex form, {@code §x§R§R§G§G§B§B}. */
    private static final Pattern HEX_SPLIT =
            Pattern.compile("[&§]x(?:[&§]([0-9A-Fa-f])){6}", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE = Pattern.compile("[&§]([0-9A-FK-ORa-fk-or])");

    private static final Map<Character, String> TAGS = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"), Map.entry('k', "obfuscated"), Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"), Map.entry('n', "underlined"), Map.entry('o', "italic"),
            Map.entry('r', "reset"));

    private Format() {
    }

    /**
     * The tag {@code {MESSAGE}} turns into, so what the player typed is carried as a component
     * rather than as text. Substituted here rather than by each caller because it is the one
     * token that is structurally different, and a caller that forgot it would put the literal
     * string "{MESSAGE}" into chat.
     */
    public static final String MESSAGE_TAG = "<message>";

    /**
     * Substitutes the tokens we know. Lookup is case-insensitive because a config written by hand
     * says {DISPLAYNAME} but nothing stops it saying {displayname}. A token with no value here is
     * left exactly as it was found, which is what lets {@link #papi} pick it up afterwards.
     */
    public static String tokens(final String format, final Map<String, String> values) {
        final Matcher m = TOKEN.matcher(format);
        final StringBuilder out = new StringBuilder();
        while (m.find()) {
            final String key = m.group(1).toUpperCase(Locale.ROOT);
            String value = values.get(key);
            if (value == null && "MESSAGE".equals(key)) value = MESSAGE_TAG;
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        return m.appendTail(out).toString();
    }

    /**
     * What is left of the {@code {token}} form, rewritten as {@code %token%} for PlaceholderAPI.
     *
     * EssentialsX resolves third-party placeholders written in its own brace syntax, which is why
     * a config in the wild carries things like {@code {simpleteams_prefix_formatted}}. Without
     * this they would render as literal text.
     */
    public static String papi(final String format) {
        return TOKEN.matcher(format).replaceAll("%$1%");
    }

    /**
     * Legacy codes rewritten as MiniMessage tags, so one parse handles both dialects.
     *
     * The tags are left open on purpose: legacy codes apply forward until something replaces
     * them, and an unclosed MiniMessage tag behaves the same way.
     */
    public static String legacyToMiniMessage(final String text) {
        String out = HEX_SPLIT.matcher(text).replaceAll(m -> "<#" + m.group(0).replaceAll("[&§xX]", "") + ">");
        out = HEX.matcher(out).replaceAll(m -> "<#" + m.group(1) + ">");
        return CODE.matcher(out).replaceAll(m ->
                "<" + TAGS.get(Character.toLowerCase(m.group(1).charAt(0))) + ">");
    }

    /**
     * The finished line. {@code resolvers} carries {@code <message>}, so the player's text arrives
     * as a component and is never parsed as markup.
     */
    public static Component render(final String format, final TagResolver... resolvers) {
        return MM.deserialize(legacyToMiniMessage(format), resolvers);
    }

    /** Which of their own codes a player is allowed to use, matching Essentials' three nodes. */
    public record Allowed(boolean colour, boolean format, boolean magic) {
        public static final Allowed NONE = new Allowed(false, false, false);
    }

    /**
     * Player-typed text, honouring only the codes the sender is permitted.
     *
     * Codes they may not use are removed rather than shown, which is what Essentials does -- a
     * message reading "&cwatch out" is a worse outcome than one with the code quietly dropped.
     * MiniMessage tags are escaped first and unconditionally: a format may use them, chat may not.
     */
    public static Component body(final String message, final Allowed allowed) {
        String out = MM.escapeTags(message);
        // Section signs never come from a client legitimately, so they go regardless.
        out = out.replace('§', '&');
        out = strip(out, HEX, allowed.colour());
        out = strip(out, HEX_SPLIT, allowed.colour());
        final Matcher m = CODE.matcher(out);
        final StringBuilder kept = new StringBuilder();
        while (m.find()) {
            final char code = Character.toLowerCase(m.group(1).charAt(0));
            final boolean permitted = code == 'r'
                    || (code == 'k' ? allowed.magic()
                    : "lmno".indexOf(code) >= 0 ? allowed.format() : allowed.colour());
            m.appendReplacement(kept, permitted ? Matcher.quoteReplacement(m.group()) : "");
        }
        return MM.deserialize(legacyToMiniMessage(m.appendTail(kept).toString()));
    }

    private static String strip(final String text, final Pattern pattern, final boolean keep) {
        return keep ? text : pattern.matcher(text).replaceAll("");
    }
}
