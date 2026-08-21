package me.lawsonhart.kremlin.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two forms every name placeholder comes in.
 *
 * A tab or nametag plugin pastes the result into a legacy-coded string, so a nickname handed over
 * as a component would arrive as its toString(). The `_formatted` twin has to survive that trip
 * with its colours intact, and the plain one has to be genuinely plain.
 */
class PlaceholdersTest {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private static Component gradientNick() {
        return MiniMessage.miniMessage()
                .deserialize("<gradient:#55FFFF:#FF55FF><bold>Lawson</bold></gradient>");
    }

    @Test
    void theFormattedFormKeepsTheColoursAsCodesTabPluginsUnderstand() {
        String formatted = LEGACY.serialize(gradientNick());

        assertTrue(formatted.contains("&"), "no codes means the gradient was lost: " + formatted);
        assertTrue(formatted.toLowerCase().contains("&l"), "bold survives too");
        assertEquals("Lawson", formatted.replaceAll("(?i)&#[0-9a-f]{6}|&[0-9a-fk-or]", ""),
                "and the name itself still reads as the name");
    }

    @Test
    void thePlainFormIsActuallyPlain() {
        String plain = PlainTextComponentSerializer.plainText().serialize(gradientNick());

        assertEquals("Lawson", plain);
        assertFalse(plain.contains("&"), "the plain form must carry no codes at all");
        assertFalse(plain.contains("§"));
    }

    /**
     * This is the bug that started this: plainText() is right for matching a typed name and
     * wrong for showing one. The two must not be interchangeable.
     */
    @Test
    void thePlainAndFormattedFormsAreNotTheSameThing() {
        assertNotEquals(PlainTextComponentSerializer.plainText().serialize(gradientNick()),
                LEGACY.serialize(gradientNick()),
                "if these matched, the formatted placeholder would be pointless");
    }

    /** A player with no nickname must not gain stray codes from the round trip. */
    @Test
    void anUnnicknamedPlayerComesBackUnchanged() {
        Component plainName = Component.text("Lawson");

        assertEquals("Lawson", LEGACY.serialize(plainName));
        assertEquals("Lawson", PlainTextComponentSerializer.plainText().serialize(plainName));
    }
}
