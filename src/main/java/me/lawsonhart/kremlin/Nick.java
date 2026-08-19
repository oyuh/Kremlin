package me.lawsonhart.kremlin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * A nickname, held as MiniMessage. Everything else is derived from it: the preview is the parsed
 * component, and the EssentialsX form is that component serialised back down.
 *
 * <p>EssentialsX never parses MiniMessage in nicknames - {@code User#getFormattedNickname()} runs
 * the stored nick through legacy colour replacement only - so the tags have to be resolved here and
 * handed over as {@code &#RRGGBB} text, which is the syntax its {@code FormatUtil} does understand.
 */
record Nick(String miniMessage) {

    /** The decorations with a MiniMessage tag and a legacy code, in menu order. */
    static final List<TextDecoration> DECORATIONS = List.of(
            TextDecoration.BOLD, TextDecoration.ITALIC, TextDecoration.UNDERLINED, TextDecoration.STRIKETHROUGH);

    private static final Map<TextDecoration, String> TAGS = Map.of(
            TextDecoration.BOLD, "bold",
            TextDecoration.ITALIC, "italic",
            TextDecoration.UNDERLINED, "underlined",
            TextDecoration.STRIKETHROUGH, "strikethrough");

    private static final LegacyComponentSerializer ESSENTIALS =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    /**
     * Builds the MiniMessage the menu produces: every stop in one gradient tag, wrapped in the
     * chosen decorations. A single stop is a flat colour, since {@code <gradient>} needs two.
     */
    static Nick gradient(final String name, final List<TextColor> stops,
                         final Set<TextDecoration> decorations) {
        final StringBuilder mini = new StringBuilder(stops.size() < 2 ? "<color" : "<gradient");
        for (final TextColor stop : stops) {
            mini.append(':').append(stop.asHexString());
        }
        mini.append('>');

        for (final TextDecoration decoration : DECORATIONS) {
            if (decorations.contains(decoration)) {
                mini.append('<').append(TAGS.get(decoration)).append('>');
            }
        }
        mini.append(name);
        for (int i = DECORATIONS.size() - 1; i >= 0; i--) {
            if (decorations.contains(DECORATIONS.get(i))) {
                mini.append("</").append(TAGS.get(DECORATIONS.get(i))).append('>');
            }
        }
        return new Nick(mini.append(stops.size() < 2 ? "</color>" : "</gradient>").toString());
    }

    /** Throws MiniMessage's ParsingException if the tags are malformed - callers report that. */
    Component component() {
        return MiniMessage.miniMessage().deserialize(this.miniMessage);
    }

    /** The same component, minus the italics an item display name would otherwise inherit. */
    Component preview() {
        return component().decoration(TextDecoration.ITALIC, false);
    }

    String essentialsNick() {
        return ESSENTIALS.serialize(component());
    }
}
