package me.lawsonhart.kremlin.chat;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotdTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static String render(final String line) {
        return PLAIN.serialize(Motd.render(line, 270));
    }

    @Test
    void centresOnPixelWidthNotCharacterCount() {
        // "lll" is narrow and "mmm" is wide, so the same three characters get different padding.
        final int thin = render("<center>lll").indexOf('l');
        final int wide = render("<center>mmm").indexOf('m');
        assertTrue(thin > wide, "narrow glyphs should be padded further: " + thin + " vs " + wide);
    }

    @Test
    void colourDoesNotCountTowardsWidth() {
        assertEquals(render("<center>hello"), render("<center><red><gradient:red:blue>hello</gradient></red>"));
    }

    @Test
    void boldGlyphsAreWiderSoTheyNeedLessPadding() {
        final int plain = render("<center>mmmmmmmmmm").indexOf('m');
        final int bold = render("<center><b>mmmmmmmmmm</b>").indexOf('m');
        assertTrue(bold < plain, "bold text is wider, so pad less: " + bold + " vs " + plain);
    }

    @Test
    void boldIsInheritedThroughAGradientsPerCharacterChildren() {
        // The real config nests a gradient inside <b>; each character arrives as its own child,
        // and the bold only exists on the wrapper.
        assertEquals(render("<center><b><gradient:red:blue>mmmmm</gradient></b>"),
                render("<center><b>mmmmm</b>"));
    }

    @Test
    void leavesUncentredLinesAlone() {
        assertEquals("hello", render("<red>hello"));
    }

    @Test
    void doesNotPadALineWiderThanTheBox() {
        assertEquals("m".repeat(60), render("<center>" + "m".repeat(60)));
    }
}
