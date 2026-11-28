package io.github.electroheavenvn.zaloxposed.utilities.richtext;

/**
 * An inline style state: what the toolbar shows at the caret, and the pending "typing style" for
 * the characters typed next. Immutable and Android-free.
 */
public final class FormatStyle {
    /** No size run: the composer's normal size. */
    public static final int NORMAL_SIZE = 0;
    /** No color run: the composer's text color. */
    public static final int NO_COLOR = -1;

    public static final FormatStyle PLAIN = new FormatStyle(false, false, false, false, NORMAL_SIZE, NO_COLOR);

    public final boolean bold;
    public final boolean italic;
    public final boolean underline;
    public final boolean strike;
    /** Size token, or {@link #NORMAL_SIZE}. */
    public final int size;
    /** 0xRRGGBB, or {@link #NO_COLOR}. */
    public final int color;

    public FormatStyle(boolean bold, boolean italic, boolean underline, boolean strike, int size, int color) {
        this.bold = bold;
        this.italic = italic;
        this.underline = underline;
        this.strike = strike;
        this.size = size;
        this.color = color;
    }

    /** True when {@code kind} (an on/off kind) is on. */
    public boolean has(FormatRun.Kind kind) {
        switch (kind) {
            case BOLD: return bold;
            case ITALIC: return italic;
            case UNDERLINE: return underline;
            case STRIKE: return strike;
            case SIZE: return size != NORMAL_SIZE;
            case COLOR: return color != NO_COLOR;
            default: return false;
        }
    }

    /**
     * The run value for {@code kind}, or {@link FormatRanges#NONE} when the kind is off. On/off
     * kinds use 1.
     */
    public int valueOf(FormatRun.Kind kind) {
        switch (kind) {
            case SIZE: return size == NORMAL_SIZE ? FormatRanges.NONE : size;
            case COLOR: return color == NO_COLOR ? FormatRanges.NONE : color;
            default: return has(kind) ? 1 : FormatRanges.NONE;
        }
    }

    /** This style with the on/off {@code kind} flipped. */
    public FormatStyle toggled(FormatRun.Kind kind) {
        switch (kind) {
            case BOLD: return new FormatStyle(!bold, italic, underline, strike, size, color);
            case ITALIC: return new FormatStyle(bold, !italic, underline, strike, size, color);
            case UNDERLINE: return new FormatStyle(bold, italic, !underline, strike, size, color);
            case STRIKE: return new FormatStyle(bold, italic, underline, !strike, size, color);
            default: return this;
        }
    }

    public FormatStyle withSize(int value) {
        return new FormatStyle(bold, italic, underline, strike, value, color);
    }

    public FormatStyle withColor(int value) {
        return new FormatStyle(bold, italic, underline, strike, size, value);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof FormatStyle)) return false;
        FormatStyle style = (FormatStyle) other;
        return style.bold == bold && style.italic == italic && style.underline == underline
            && style.strike == strike && style.size == size && style.color == color;
    }

    @Override
    public int hashCode() {
        int flags = (bold ? 1 : 0) | (italic ? 2 : 0) | (underline ? 4 : 0) | (strike ? 8 : 0);
        return (flags * 31 + size) * 31 + color;
    }

    @Override
    public String toString() {
        return "FormatStyle(b=" + bold + ",i=" + italic + ",u=" + underline + ",s=" + strike
            + ",size=" + size + ",color=" + color + ")";
    }
}
