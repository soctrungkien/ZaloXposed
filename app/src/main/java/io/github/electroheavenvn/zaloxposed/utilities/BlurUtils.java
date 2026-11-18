package io.github.electroheavenvn.zaloxposed.utilities;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Small pure-Java two-pass box blur, used for the custom background image. */
public final class BlurUtils
{
    private BlurUtils() { }

    public static Bitmap blur(Bitmap bitmap, int radius)
    {
        if (bitmap == null || radius <= 0)
            return bitmap;
        int r = Math.max(1, Math.min(25, radius));
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0)
            return bitmap;

        Bitmap output = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        int[] pixels = new int[width * height];
        int[] temp = new int[pixels.length];
        output.getPixels(pixels, 0, width, 0, 0, width, height);
        boxBlurHorizontal(pixels, temp, width, height, r);
        boxBlurVertical(temp, pixels, width, height, r);
        output.setPixels(pixels, 0, width, 0, 0, width, height);
        return output;
    }

    private static void boxBlurHorizontal(int[] source, int[] target,
        int width, int height, int radius)
    {
        int window = radius * 2 + 1;
        for (int y = 0; y < height; y++)
        {
            int row = y * width;
            int a = 0, red = 0, green = 0, blue = 0;
            for (int k = -radius; k <= radius; k++)
            {
                int pixel = source[row + clamp(k, width)];
                a += Color.alpha(pixel);
                red += Color.red(pixel);
                green += Color.green(pixel);
                blue += Color.blue(pixel);
            }
            for (int x = 0; x < width; x++)
            {
                target[row + x] = Color.argb(a / window,
                    red / window, green / window, blue / window);
                int remove = source[row + clamp(x - radius, width)];
                int add = source[row + clamp(x + radius + 1, width)];
                a += Color.alpha(add) - Color.alpha(remove);
                red += Color.red(add) - Color.red(remove);
                green += Color.green(add) - Color.green(remove);
                blue += Color.blue(add) - Color.blue(remove);
            }
        }
    }

    private static void boxBlurVertical(int[] source, int[] target,
        int width, int height, int radius)
    {
        int window = radius * 2 + 1;
        for (int x = 0; x < width; x++)
        {
            int a = 0, red = 0, green = 0, blue = 0;
            for (int k = -radius; k <= radius; k++)
            {
                int pixel = source[clamp(k, height) * width + x];
                a += Color.alpha(pixel);
                red += Color.red(pixel);
                green += Color.green(pixel);
                blue += Color.blue(pixel);
            }
            for (int y = 0; y < height; y++)
            {
                target[y * width + x] = Color.argb(a / window,
                    red / window, green / window, blue / window);
                int remove = source[clamp(y - radius, height) * width + x];
                int add = source[clamp(y + radius + 1, height) * width + x];
                a += Color.alpha(add) - Color.alpha(remove);
                red += Color.red(add) - Color.red(remove);
                green += Color.green(add) - Color.green(remove);
                blue += Color.blue(add) - Color.blue(remove);
            }
        }
    }

    private static int clamp(int value, int size)
    {
        if (value < 0)
            return 0;
        return Math.min(value, size - 1);
    }
}
