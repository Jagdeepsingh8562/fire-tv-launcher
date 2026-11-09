package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Outfit in four static weights from assets/fonts. The res/font files are variable fonts, which Android only
 * reads (by weight) from 8.0; Fire OS 5 needs one file per weight. Cut with tools/venv fonttools varLib.instancer.
 */
final class Fonts {
    private static Typeface light, regular, medium, semibold, antonio;

    private Fonts() {}

    static Typeface light(Context c) { return light != null ? light : (light = load(c, "Light")); }
    static Typeface regular(Context c) { return regular != null ? regular : (regular = load(c, "Regular")); }
    static Typeface medium(Context c) { return medium != null ? medium : (medium = load(c, "Medium")); }
    /** Antonio Light, couch-tv's tall condensed clock face (SIL OFL), as one static file. */
    static Typeface antonio(Context c) {
        if (antonio != null) return antonio;
        try {
            return antonio = Typeface.createFromAsset(c.getAssets(), "fonts/Antonio-Light.ttf");
        } catch (RuntimeException e) {
            return light(c);
        }
    }
    static Typeface semibold(Context c) { return semibold != null ? semibold : (semibold = load(c, "SemiBold")); }

    private static Typeface load(Context c, String weight) {
        try {
            return Typeface.createFromAsset(c.getAssets(), "fonts/Outfit-" + weight + ".ttf");
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }
}
