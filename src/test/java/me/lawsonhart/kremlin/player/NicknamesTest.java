package me.lawsonhart.kremlin.player;

import me.lawsonhart.kremlin.core.Format;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nicknames moved out of EssentialsX and into users.yml, stored as MiniMessage. The conversion is
 * one-way and one-time, so getting it wrong silently loses everybody's nickname.
 */
class NicknamesTest {

    private static String plain(final String miniMessage) {
        return PlainTextComponentSerializer.plainText()
                .serialize(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    /**
     * What EssentialsX wrote into userdata: legacy codes, hex as the section-x form, one code per
     * character for a gradient. All of it has to survive the trip into MiniMessage.
     */
    @Test
    void anEssentialsNicknameConvertsWithoutLosingItsColours() {
        String stored = "§x§2§7§4§5§C§8D§x§1§4§7§2§E§4a§x§0§0§9§F§F§Fl";
        String mini = Format.legacyToMiniMessage(stored);

        assertEquals("<#2745C8>D<#1472E4>a<#009FFF>l", mini, "one colour per character, kept");
        assertEquals("Dal", plain(mini), "and the name itself still reads as the name");
    }

    @Test
    void theAmpersandFormConvertsTheSameWay() {
        assertEquals("<green><bold>Lawson", Format.legacyToMiniMessage("&a&lLawson"));
        assertEquals("Lawson", plain(Format.legacyToMiniMessage("&a&lLawson")));
    }

    /** A nickname with no codes at all must come through untouched, not mangled. */
    @Test
    void aPlainNicknameIsLeftAlone() {
        assertEquals("Lawson", Format.legacyToMiniMessage("Lawson"));
    }

    /**
     * ignore-colors-in-max-nick-length is on in the server config, and it has to be: a single
     * gradient is far longer in tags than on screen, so counting the raw string would refuse
     * nicknames that render to three letters.
     */
    @Test
    void lengthIsMeasuredOnWhatIsActuallyShown() {
        String gradient = "&#FFC0CBL&#A6A6A6a&#FFC0CBw";

        assertFalse(Nicknames.tooLong(gradient, 16, true), "renders to 3 characters, well inside 16");
        assertTrue(Nicknames.tooLong(gradient, 16, false),
                "counting the codes would refuse a three-letter nickname");
    }

    @Test
    void theLimitStillBitesOnALongPlainNickname() {
        assertFalse(Nicknames.tooLong("Lawson", 32, true));
        assertTrue(Nicknames.tooLong("L".repeat(33), 32, true));
        assertFalse(Nicknames.tooLong("L".repeat(32), 32, true), "exactly the limit is allowed");
    }

    /** The regex from the server's Essentials config, which the importer carries across. */
    @Test
    void theImportedRegexAcceptsColouredNicknamesAndRefusesSpaces() {
        var allowed = java.util.regex.Pattern.compile("^[a-zA-Z_0-9§&<>:#-]+$");

        assertTrue(allowed.matcher("Lawson").matches());
        assertTrue(allowed.matcher("&aLawson").matches());
        assertTrue(allowed.matcher("&#FFC0CBLawson").matches());
        assertFalse(allowed.matcher("Law son").matches(), "a space would let a nickname fake two names");
        assertFalse(allowed.matcher("Law.son").matches());
    }
}
