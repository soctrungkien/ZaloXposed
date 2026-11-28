package io.github.electroheavenvn.zaloxposed.utilities.richtext;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;


/**
 * Zalo's rich-text limits: font size range and color whitelist. Read from the same remote-config
 * keys Zalo's own rtf parser uses, with the same defaults.
 */
public final class FormatConfig {
    public static final String KEY_SIZE_MIN = "chat@message@rtf_configs@rtf_font_size_min";
    public static final String KEY_SIZE_MAX = "chat@message@rtf_configs@rtf_font_size_max";
    public static final String KEY_COLOR_WHITELIST = "chat@message@rtf_configs@rtf_font_color_whitelist";
    public static final int DEFAULT_SIZE_MIN = 13;
    public static final int DEFAULT_SIZE_MAX = 20;
    public static final String DEFAULT_COLOR_WHITELIST = "#050a19,#15a85f,#f7b503,#f27806,#db342e";

    public static final FormatConfig DEFAULT =
        parse(DEFAULT_SIZE_MIN, DEFAULT_SIZE_MAX, DEFAULT_COLOR_WHITELIST);

    public final int sizeMin;
    public final int sizeMax;
    /** Lowercase {@code #rrggbb} entries, in config order. */
    public final Set<String> colors;

    private FormatConfig(int sizeMin, int sizeMax, Set<String> colors) {
        this.sizeMin = sizeMin;
        this.sizeMax = sizeMax;
        this.colors = colors;
    }

    /** Config from raw values; an inverted size range falls back to the defaults. */
    public static FormatConfig parse(int sizeMin, int sizeMax, String whitelist) {
        if (sizeMin <= 0 || sizeMax < sizeMin) {
            sizeMin = DEFAULT_SIZE_MIN;
            sizeMax = DEFAULT_SIZE_MAX;
        }
        Set<String> colors = new LinkedHashSet<>();
        String source = whitelist == null || whitelist.trim().isEmpty() ? DEFAULT_COLOR_WHITELIST : whitelist;
        for (String entry : source.split(",")) {
            String color = entry.trim().toLowerCase(Locale.ROOT);
            if (color.matches("#[0-9a-f]{6}")) colors.add(color);
        }
        return new FormatConfig(sizeMin, sizeMax, Collections.unmodifiableSet(colors));
    }

    /** True when 0xRRGGBB {@code rgb} is an allowed color. */
    public boolean allowsColor(int rgb) {
        return colors.contains("#" + hex(rgb));
    }

    public int clampSize(int size) {
        return Math.max(sizeMin, Math.min(sizeMax, size));
    }

    static String hex(int rgb) {
        return String.format(Locale.ROOT, "%06x", rgb & 0xFFFFFF);
    }

    /** The live remote config, or {@link #DEFAULT} when it cannot be read. */
    public static FormatConfig current() {
        try {
            int min = remoteInt(KEY_SIZE_MIN, DEFAULT_SIZE_MIN);
            int max = remoteInt(KEY_SIZE_MAX, DEFAULT_SIZE_MAX);
            String whitelist = remoteString(KEY_COLOR_WHITELIST, DEFAULT_COLOR_WHITELIST);
            FormatConfig config = parse(min, max, whitelist);
            logIfChanged(config, min, max, whitelist, null);
            return config;
        } catch (Throwable failure) {
            LogAdapter.swallowedOnce("FormatConfig", "remote config unavailable, using defaults", failure);
            logIfChanged(DEFAULT, DEFAULT_SIZE_MIN, DEFAULT_SIZE_MAX, DEFAULT_COLOR_WHITELIST,
                failure.getClass().getSimpleName());
            return DEFAULT;
        }
    }

    private static final FormatLog.Latch LOGGED = new FormatLog.Latch();

    /**
     * Logs the effective config the first time in this process and whenever it changes. A value equal
     * to Zalo's default is marked {@code def}: Zalo's getter returns the default for a missing key, so
     * "remote" is only certain when the value differs.
     */
    private static void logIfChanged(FormatConfig config, int rawMin, int rawMax, String rawWhitelist, String failure) {
        String source;
        if (failure != null) {
            source = "defaults (remote config failed: " + failure + ")";
        } else if (config.sizeMin != rawMin || config.sizeMax != rawMax) {
            source = "defaults for size (invalid remote range " + rawMin + ".." + rawMax + ")";
        } else if (rawMin == DEFAULT_SIZE_MIN && rawMax == DEFAULT_SIZE_MAX
                && DEFAULT_COLOR_WHITELIST.equals(rawWhitelist)) {
            source = "default values";
        } else {
            source = "remote";
        }
        int rawEntries = rawWhitelist == null || rawWhitelist.trim().isEmpty() ? 0 : rawWhitelist.split(",").length;
        String state = "size=" + config.sizeMin + (config.sizeMin == DEFAULT_SIZE_MIN ? "(def)" : "")
            + ".." + config.sizeMax + (config.sizeMax == DEFAULT_SIZE_MAX ? "(def)" : "")
            + " colors=" + config.colors.size() + (DEFAULT.colors.equals(config.colors) ? "(def)" : "")
            + " [" + String.join(",", config.colors) + "]"
            + (rawEntries == 0 ? " whitelist=blank->default" : "")
            + (rawEntries > config.colors.size() ? " droppedEntries=" + (rawEntries - config.colors.size()) : "")
            + " source=" + source;
        if (LOGGED.changed(state)) LogAdapter.i("FormatConfig", FormatLog.CONFIG + state);
    }

    /** Patch-generated body: Zalo's remote-config int getter. Returns {@code fallback} unpatched. */
    public static int remoteInt(String key, int fallback) {
        return fallback;
    }

    /** Patch-generated body: Zalo's remote-config string getter. Returns {@code fallback} unpatched. */
    public static String remoteString(String key, String fallback) {
        return fallback;
    }
}
