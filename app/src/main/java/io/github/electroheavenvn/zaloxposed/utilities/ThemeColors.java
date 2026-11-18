package io.github.electroheavenvn.zaloxposed.utilities;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;

import java.util.Locale;

/** Accent color presets, Monet extraction and shared color helpers. */
public final class ThemeColors
{
    public static final String MONET = "monet";

    public static final String[] NAMES = {
        "Zalo blue", "Tím", "Xanh dương", "Xanh lá", "Cam", "Đỏ", "Hồng",
        "Teal", "Indigo", "Vàng", "Cyan", "Nâu", "Slate", "Monet", "Custom"
    };

    public static final String[] VALUES = {
        "#0068FF", "#6750A4", "#0061A4", "#386A20", "#8A4B00", "#A52A2A",
        "#A3005A", "#006A6A", "#4F5F90", "#725C00", "#006874", "#6F4A2B",
        "#53606B", MONET, ""
    };

    /** The five stock Zalo blue shades replaced by the chosen accent. */
    public static final int[] STOCK_BLUES = {
        0xFF0068FF, 0xFF2468D2, 0xFF005AE0, 0xFF0085FF, 0xFF8FBFFF
    };

    private ThemeColors() { }

    /** Resolves the configured accent, including dynamic Monet colors. */
    public static int getAccent(Context context)
    {
        String value = Config.getAccentColor();
        if (MONET.equalsIgnoreCase(value) && context != null)
        {
            int monet = monetAccent(context);
            if (monet != 0)
                return monet;
        }
        return parse(value, 0xFF0068FF);
    }

    public static String currentLabel(Context context)
    {
        String value = Config.getAccentColor();
        if (MONET.equalsIgnoreCase(value))
            return Utils.IsEnglish() ? "Monet (dynamic)" : "Monet (theo hình nền)";
        for (int i = 0; i < VALUES.length; i++)
        {
            if (VALUES[i].equalsIgnoreCase(value))
                return NAMES[i] + " " + value.toUpperCase(Locale.US);
        }
        return (Utils.IsEnglish() ? "Custom" : "Tuỳ chỉnh") + " " + value.toUpperCase(Locale.US);
    }

    public static int parse(String value, int fallback)
    {
        if (value == null || value.isEmpty())
            return fallback;
        try
        {
            return Color.parseColor(value);
        }
        catch (Throwable ignored)
        {
            return fallback;
        }
    }

    public static int withAlpha(int color, int alpha)
    {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int monetAccent(Context context)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            return 0;
        try
        {
            WallpaperColors colors = WallpaperManager.getInstance(context)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (colors == null || colors.getPrimaryColor() == null)
                return 0;
            return colors.getPrimaryColor().toArgb();
        }
        catch (Throwable ignored)
        {
            return 0;
        }
    }
}
