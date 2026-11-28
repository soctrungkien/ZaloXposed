package io.github.electroheavenvn.zaloxposed.utilities;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
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
        0xFF0068FF, 0xFF2468D2, 0xFF005AE0, 0xFF0085FF, 0xFF8FBFFF, 0xFF1194FF
    };

    private static volatile long monetUpdatedAt;
    private static volatile int cachedMonet;

    private ThemeColors() { }

    /** Resolves the configured accent, including dynamic Monet colors. */
    public static int getAccent(Context context)
    {
        String value = Config.getAccentColor();
        if (MONET.equalsIgnoreCase(value))
        {
            int monet = getMonetAccent(context);
            if (monet != 0)
                return monet;
            return 0xFF0068FF;
        }
        return parse(value, 0xFF0068FF);
    }

    /** Cached, best-effort Material You accent from the current wallpaper. */
    public static int getMonetAccent(Context context)
    {
        long now = System.currentTimeMillis();
        if (cachedMonet != 0 && now - monetUpdatedAt < 300_000L)
            return cachedMonet;
        int color = extractMonet(context);
        if (color != 0)
        {
            cachedMonet = color;
            monetUpdatedAt = now;
        }
        return color;
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

    private static int extractMonet(Context context)
    {
        if (context == null)
            return 0;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        {
            // Android 12+: use the platform Monotone/WallpaperColors API only.
            // Do NOT substitute a sampled image here - the system palette is
            // the requested source on modern Android.
            try
            {
                WallpaperManager manager = WallpaperManager.getInstance(context);
                int[] flags = {WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK};
                for (int flag : flags)
                {
                    WallpaperColors colors = manager.getWallpaperColors(flag);
                    if (colors == null)
                        continue;
                    Color[] candidates = {
                        colors.getPrimaryColor(),
                        colors.getSecondaryColor(),
                        colors.getTertiaryColor()
                    };
                    int best = 0;
                    float bestScore = -1f;
                    for (Color candidate : candidates)
                    {
                        if (candidate == null)
                            continue;
                        int argb = candidate.toArgb();
                        float[] hsv = new float[3];
                        Color.colorToHSV(argb, hsv);
                        float score = hsv[1] * 1.4f + hsv[2] * 0.6f;
                        if (score > bestScore)
                        {
                            bestScore = score;
                            best = argb;
                        }
                    }
                    if (best != 0)
                        return best;
                }
            }
            catch (Throwable ignored) { }
            return 0;
        }

        // Android 11 and older: no WallpaperColors API, sample the wallpaper image.
        try
        {
            Drawable wallpaper = WallpaperManager.getInstance(context).getDrawable();
            if (wallpaper != null)
            {
                final int size = 48;
                Bitmap sample = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(sample);
                wallpaper.setBounds(0, 0, size, size);
                wallpaper.draw(canvas);
                int[] pixels = new int[size * size];
                sample.getPixels(pixels, 0, size, 0, 0, size, size);
                int best = 0;
                float bestScore = -1f;
                for (int pixel : pixels)
                {
                    if (Color.alpha(pixel) < 128)
                        continue;
                    float[] hsv = new float[3];
                    Color.colorToHSV(pixel, hsv);
                    float score = hsv[1] * 1.4f + hsv[2] * 0.6f;
                    if (score > bestScore)
                    {
                        bestScore = score;
                        best = pixel;
                    }
                }
                if (best != 0)
                    return best;
            }
        }
        catch (Throwable ignored) { }
        return 0;
    }
}
