package me.lawsonhart.kremlin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Self-check for the palette, the MiniMessage build and the EssentialsX hand-off: ./gradlew nickCheck */
public final class NickCheck {

    /** A colour in EssentialsX nick input: &#RRGGBB, or a single legacy colour character. */
    private static final Pattern COLOUR = Pattern.compile("(?i)&#[0-9a-f]{6}|&[0-9a-f]");

    public static void main(final String[] args) {
        final TextColor aqua = TextColor.color(0x55FFFF);
        final TextColor magenta = TextColor.color(0xFF55FF);

        // The picker's grid: 52 colours, all distinct, and two slots left for its buttons.
        check(Palette.COLOURS.size() == 52, "palette has 52 colours, got " + Palette.COLOURS.size());
        check(new HashSet<>(Palette.COLOURS).size() == Palette.COLOURS.size(), "palette has no duplicates");
        check(Palette.COLOURS.size() <= ColourPicker.SLOT_CUSTOM,
                "palette fits before the custom-hex slot");

        final Nick two = Nick.gradient("Lawson", List.of(aqua, magenta), Set.of(TextDecoration.BOLD));
        System.out.println("minimessage : " + two.miniMessage());
        System.out.println("essentials  : " + two.essentialsNick());

        check(two.miniMessage().equals("<gradient:#55FFFF:#FF55FF><bold>Lawson</bold></gradient>"),
                "two stops build one gradient tag: " + two.miniMessage());
        check(plain(two).equals("Lawson"), "parses back to the plain name");

        // Adventure writes a colour that matches a named one as its legacy character (#55FFFF -> &b),
        // so assert on the colours the codes mean, not on the text of the codes.
        final List<TextColor> colours = colours(two.essentialsNick());
        check(colours.size() == 6, "one colour per character, got " + colours.size());
        check(colours.get(0).equals(aqua), "starts at the first stop, got " + colours.get(0).asHexString());
        check(colours.get(5).equals(magenta), "ends at the last stop, got " + colours.get(5).asHexString());

        // Ten stops: every one lands in the tag, and the ends still pin to the outer stops.
        final List<TextColor> ten = new ArrayList<>();
        for (int i = 0; i < NickMenu.MAX_STOPS; i++) {
            ten.add(Palette.COLOURS.get(i));
        }
        final Nick many = Nick.gradient("Lawson_1234", ten, Set.of());
        check(count(many.miniMessage(), "#") == NickMenu.MAX_STOPS,
                "all ten stops reach the gradient tag: " + many.miniMessage());
        final List<TextColor> manyColours = colours(many.essentialsNick());
        check(manyColours.size() == 11, "one colour per character across 11 characters, got " + manyColours.size());
        check(manyColours.get(0).equals(ten.get(0)), "ten-stop gradient starts on stop one");
        check(manyColours.get(10).equals(ten.get(9)), "ten-stop gradient ends on stop ten");

        // More stops than characters must not throw or lose the text.
        final Nick crowded = Nick.gradient("ab", ten, Set.of());
        check(plain(crowded).equals("ab"), "more stops than characters still renders the name");

        // A single stop is a flat colour, not a broken gradient tag.
        final Nick one = Nick.gradient("L", List.of(aqua), Set.of());
        check(one.miniMessage().equals("<color:#55FFFF>L</color>"), "one stop is a flat colour: " + one.miniMessage());
        check(colours(one.essentialsNick()).size() == 1, "one stop gives one colour: " + one.essentialsNick());

        final String essentials = two.essentialsNick();
        check(essentials.replaceAll("(?i)&#[0-9a-f]{6}|&[0-9a-fk-or]", "").equals("Lawson"),
                "strips back to the 6 visible characters EssentialsX counts");
        check(essentials.toLowerCase(Locale.ROOT).contains("&l"), "bold survives the hand-off");

        // What EssentialsX validates: & codes converted to section codes, matched against its regex.
        final String converted = essentials
                .replaceAll("(?i)&#([0-9a-f])([0-9a-f])([0-9a-f])([0-9a-f])([0-9a-f])([0-9a-f])",
                        "§x§$1§$2§$3§$4§$5§$6")
                .replace("&", "§");
        check(converted.matches("^[a-zA-Z_0-9§]+$"), "passes the default Essentials nick regex");

        // No decorations means no stray codes.
        final Nick undecorated = Nick.gradient("ab", List.of(aqua, magenta), Set.of());
        check(!undecorated.essentialsNick().matches("(?i).*&[k-or].*"),
                "no decoration codes when none are picked: " + undecorated.essentialsNick());

        // Two at once: a colour code resets formatting in legacy text, so BOTH codes have to be
        // re-emitted on every single character or the pair silently comes apart mid-name.
        final Nick pair = Nick.gradient("Lawson", List.of(aqua, magenta),
                Set.of(TextDecoration.BOLD, TextDecoration.ITALIC));
        check(pair.miniMessage().equals("<gradient:#55FFFF:#FF55FF><bold><italic>Lawson</italic></bold></gradient>"),
                "bold and italic nest: " + pair.miniMessage());
        final String pairLegacy = pair.essentialsNick().toLowerCase(Locale.ROOT);
        check(count(pairLegacy, "&l") == 6, "bold on all 6 characters: " + pairLegacy);
        check(count(pairLegacy, "&o") == 6, "italic on all 6 characters: " + pairLegacy);
        check(count(pairLegacy, "&l&o") == 6, "the pair stays together on every character: " + pairLegacy);

        // All four decorations become tags, in menu order, and survive down to legacy codes.
        final Nick decorated = Nick.gradient("x", List.of(aqua, magenta), Set.of(TextDecoration.BOLD,
                TextDecoration.ITALIC, TextDecoration.UNDERLINED, TextDecoration.STRIKETHROUGH));
        check(decorated.miniMessage().equals(
                        "<gradient:#55FFFF:#FF55FF><bold><italic><underlined><strikethrough>x"
                                + "</strikethrough></underlined></italic></bold></gradient>"),
                "tags nest in menu order: " + decorated.miniMessage());
        final String decoratedLegacy = decorated.essentialsNick().toLowerCase(Locale.ROOT);
        for (final String code : new String[] {"&l", "&o", "&n", "&m"}) {
            check(decoratedLegacy.contains(code), "legacy keeps " + code + ": " + decoratedLegacy);
        }

        System.out.println("all checks passed");
    }

    private static List<TextColor> colours(final String essentialsNick) {
        final List<TextColor> found = new ArrayList<>();
        final Matcher matcher = COLOUR.matcher(essentialsNick);
        while (matcher.find()) {
            final String token = matcher.group();
            found.add(token.startsWith("&#")
                    ? TextColor.fromHexString("#" + token.substring(2))
                    : LegacyComponentSerializer.parseChar(token.charAt(1)).color());
        }
        return found;
    }

    private static String plain(final Nick nick) {
        return PlainTextComponentSerializer.plainText().serialize(nick.component());
    }

    private static int count(final String haystack, final String needle) {
        return haystack.split(Pattern.quote(needle), -1).length - 1;
    }

    private static void check(final boolean condition, final String what) {
        if (!condition) {
            throw new AssertionError("FAILED: " + what);
        }
        System.out.println("  ok: " + what);
    }
}
