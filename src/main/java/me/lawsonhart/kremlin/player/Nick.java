package me.lawsonhart.kremlin.player;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * A nickname, held as MiniMessage. The preview is simply the parsed component, and that is the
 * form {@link Nicknames} stores.
 *
 * <p>It used to be serialised down to {@code &#RRGGBB} text and handed to EssentialsX' /nick,
 * which was the only syntax its {@code FormatUtil} understood. {@link #legacy()} is what did that;
 * it is kept because reading the colour back out per character is how the self-check measures a
 * gradient, not because anything still speaks legacy.
 */
public record Nick(String miniMessage) {

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

    /** The gradient flattened to one legacy colour code per character. Measurement only. */
    String legacy() {
        return ESSENTIALS.serialize(component());
    }
}
