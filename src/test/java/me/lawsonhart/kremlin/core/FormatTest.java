package me.lawsonhart.kremlin.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chat formatting has to reproduce an existing EssentialsChat config exactly, so these pin the
 * real format string off the server rather than an invented one.
 */
class FormatTest {

    /** Verbatim from the EssentialsChat config this replaced. */
    private static final String REAL =
            "{simpleteams_prefix_formatted} {PREFIX}{DISPLAYNAME}&r: {MESSAGE}";

    private static final Map<String, String> VALUES = Map.of(
            "PREFIX", "&7[Member] ",
            "DISPLAYNAME", "&bLawson",
            "USERNAME", "Lawson",
            "GROUP", "default");

    private static String plain(final Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void knownPlaceholdersAreSubstitutedAndUnknownOnesSurvive() {
        String out = Format.tokens(REAL, VALUES);

        assertTrue(out.contains("&7[Member] "), "prefix substituted");
        assertTrue(out.contains("&bLawson"), "display name substituted");
        assertTrue(out.contains("{simpleteams_prefix_formatted}"),
                "a placeholder we do not own must survive for PlaceholderAPI to resolve");
        assertEquals(Format.MESSAGE_TAG, out.substring(out.length() - Format.MESSAGE_TAG.length()),
                "the message becomes a tag so it can arrive as a component, not as text");
    }

    /** The brace form is EssentialsX's; PlaceholderAPI wants percent signs. */
    @Test
    void leftoverPlaceholdersAreHandedToPlaceholderApi() {
        assertEquals("%simpleteams_prefix_formatted% hello",
                Format.papi("{simpleteams_prefix_formatted} hello"));
        assertEquals("no braces here", Format.papi("no braces here"));
    }

    @Test
    void placeholderLookupIsCaseInsensitive() {
        assertEquals("Lawson", Format.tokens("{username}", VALUES));
        assertEquals("Lawson", Format.tokens("{UserName}", VALUES));
    }

    @Test
    void legacyCodesBecomeMiniMessageTags() {
        assertEquals("<green>go", Format.legacyToMiniMessage("&ago"));
        assertEquals("<bold><red>stop", Format.legacyToMiniMessage("&l&cstop"));
        assertEquals("<reset>plain", Format.legacyToMiniMessage("&rplain"));
        assertEquals("<#FFC0CB>pink", Format.legacyToMiniMessage("&#FFC0CBpink"));
        assertEquals("<#2745C8>Da", Format.legacyToMiniMessage("§x§2§7§4§5§C§8Da"),
                "the serialised hex form a stored nickname carries");
        assertEquals("nothing to do", Format.legacyToMiniMessage("nothing to do"));
    }

    /** The whole line, end to end, the way chat actually renders it. */
    @Test
    void theRealFormatRendersWithTheMessageAsAComponent() {
        String resolved = Format.tokens(REAL, VALUES);
        Component line = Format.render(resolved,
                Placeholder.component("message", Component.text("hi there")));

        assertEquals("{simpleteams_prefix_formatted} [Member] Lawson: hi there", plain(line));
    }

    @Test
    void aGroupFormatCanAddDecorationInFrontOfTheRest() {
        Component line = Format.render(Format.tokens("&6★ &r" + REAL, VALUES),
                Placeholder.component("message", Component.text("yo")));
        assertTrue(plain(line).startsWith("★ "), "the admin format keeps its marker: " + plain(line));
    }

    // ------------------------------------------------------------- the safety half

    @Test
    void aPlayerCannotInjectMiniMessageIntoTheFormat() {
        Component body = Format.body("<red>NOT RED</red> <bold>x", new Format.Allowed(true, true, true));
        assertEquals("<red>NOT RED</red> <bold>x", plain(body),
                "tags typed in chat are text, never markup, whatever the permissions say");
    }

    @Test
    void theMessageCannotEscapeIntoTheSurroundingFormat() {
        Component line = Format.render(Format.tokens(REAL, VALUES),
                Placeholder.component("message", Format.body("</gradient>{PREFIX}", Format.Allowed.NONE)));
        assertTrue(plain(line).endsWith(": </gradient>{PREFIX}"),
                "a placeholder typed in chat stays literal: " + plain(line));
    }

    @Test
    void colourCodesInChatNeedThePermission() {
        assertEquals("hello", plain(Format.body("&chello", Format.Allowed.NONE)),
                "a code they may not use is removed, not shown");
        assertEquals(NamedTextColor.RED,
                Format.body("&chello", new Format.Allowed(true, false, false)).color());
    }

    @Test
    void formatAndMagicAreSeparatePermissionsFromColour() {
        Format.Allowed colourOnly = new Format.Allowed(true, false, false);
        assertEquals("bold?", plain(Format.body("&lbold?", colourOnly)));
        assertNotEquals(TextDecoration.State.TRUE,
                Format.body("&lbold?", colourOnly).decoration(TextDecoration.BOLD),
                "colour permission alone must not grant bold");

        assertEquals(TextDecoration.State.TRUE,
                Format.body("&lbold!", new Format.Allowed(false, true, false))
                        .decoration(TextDecoration.BOLD));
        assertEquals("spin", plain(Format.body("&kspin", new Format.Allowed(true, true, false))),
                "magic is its own node again");
    }

    @Test
    void sectionSignsFromAClientAreNeverHonoured() {
        assertEquals("hello", plain(Format.body("§chello", Format.Allowed.NONE)));
    }
}
