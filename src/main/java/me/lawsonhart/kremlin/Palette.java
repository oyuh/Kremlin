package me.lawsonhart.kremlin;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.util.HSVLike;

/** The picker's colours: twelve hues in four shades, plus four greys. 52 in total. */
final class Palette {

    /** Saturation and value for each shade of a hue: vivid, deep, pastel, dark. */
    private static final float[][] SHADES = {
            {1.00f, 1.00f}, {1.00f, 0.62f}, {0.45f, 1.00f}, {0.85f, 0.38f}};

    private static final List<TextColor> GREYS = List.of(
            TextColor.color(0xFFFFFF), TextColor.color(0xBFBFBF),
            TextColor.color(0x6E6E6E), TextColor.color(0x1A1A1A));

    static final List<TextColor> COLOURS = build();

    private Palette() {
    }

    private static List<TextColor> build() {
        final List<TextColor> colours = new ArrayList<>(52);
        // Shade-major so each row of the picker is one shade sweeping through the hues.
        for (final float[] shade : SHADES) {
            for (int hue = 0; hue < 12; hue++) {
                colours.add(TextColor.color(HSVLike.hsvLike(hue / 12.0f, shade[0], shade[1])));
            }
        }
        colours.addAll(GREYS);
        return List.copyOf(colours);
    }
}
