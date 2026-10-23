package io.github.electroheavenvn.zaloxposed.utilities;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Loads and applies the font selected from the ZaloXposed settings screen.
 *
 * The selected font is copied into Zalo's private files directory so it stays
 * available after the content Uri loses its permission. No font is applied
 * when the feature is disabled or the file cannot be decoded.
 */
public final class CustomFont
{
    private CustomFont() { }

    private static Typeface cachedTypeface;
    private static String cachedPath;
    private static long cachedLastModified;

    public static File getFontDir(Context context)
    {
        File dir = new File(context.getFilesDir(), "fonts");
        if (!dir.exists())
            dir.mkdirs();
        return dir;
    }

    public static File getFontFile(Context context)
    {
        try
        {
            String path = Config.getCustomFontPath();
            if (path != null && !path.isEmpty())
            {
                File configured = new File(path);
                if (configured.isFile())
                    return configured;
            }
            File dir = getFontDir(context);
            File[] files = dir.listFiles();
            if (files == null)
                return null;
            for (File file : files)
            {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (file.isFile() && name.startsWith("custom_font.") && !name.endsWith(".tmp"))
                    return file;
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
        return null;
    }

    public static boolean hasFont(Context context)
    {
        return getFontFile(context) != null;
    }

    public static String getDisplayName(Context context)
    {
        String name = Config.getCustomFontName();
        if (name != null && !name.isEmpty())
            return name;
        File file = getFontFile(context);
        return file != null ? file.getName() : "";
    }

    /**
     * Returns the selected typeface, or null when the feature is disabled or
     * the stored font cannot be loaded. The typeface is cached by path and
     * modification time so replacing the file invalidates the cache.
     */
    public static synchronized Typeface load(Context context)
    {
        try
        {
            if (!Config.getCustomFontEnabled())
                return null;
            File file = getFontFile(context);
            if (file == null || !file.isFile())
                return null;
            String path = file.getAbsolutePath();
            long lastModified = file.lastModified();
            if (cachedTypeface != null && path.equals(cachedPath) && lastModified == cachedLastModified)
                return cachedTypeface;
            Typeface typeface = Typeface.createFromFile(file);
            cachedTypeface = typeface;
            cachedPath = path;
            cachedLastModified = lastModified;
            return typeface;
        }
        catch (Throwable t)
        {
            Logger.e("Cannot load custom font: " + t.getMessage());
            return null;
        }
    }

    public static Typeface getTypeface(Context context, int style)
    {
        Typeface base = load(context);
        if (base == null)
            return null;
        try
        {
            int typefaceStyle = style & (Typeface.BOLD | Typeface.ITALIC);
            if (typefaceStyle == 0)
                return base;
            return Typeface.create(base, typefaceStyle);
        }
        catch (Throwable t)
        {
            Logger.e(t);
            return base;
        }
    }

    /**
     * Keeps the weight/italic of an existing message typeface while replacing
     * its family with the selected font.
     */
    public static Typeface forMessageModule(Context context, Typeface original)
    {
        Typeface base = load(context);
        if (base == null)
            return null;
        if (original == null)
            return base;
        try
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                return Typeface.create(base, original.getWeight(), original.isItalic());
            return Typeface.create(base, original.getStyle());
        }
        catch (Throwable t)
        {
            Logger.e(t);
            return base;
        }
    }

    public static void forChatPaint(Context context, TextPaint paint)
    {
        if (paint == null)
            return;
        Typeface base = load(context);
        if (base == null)
            return;
        try
        {
            Typeface current = paint.getTypeface();
            if (current == null)
            {
                paint.setTypeface(base);
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                paint.setTypeface(Typeface.create(base, current.getWeight(), current.isItalic()));
            else
                paint.setTypeface(Typeface.create(base, current.getStyle()));
        }
        catch (Throwable t)
        {
            Logger.e(t);
            paint.setTypeface(base);
        }
    }

    public static void invalidate()
    {
        cachedTypeface = null;
        cachedPath = null;
        cachedLastModified = 0L;
    }

    /**
     * Copies a font picked through ACTION_OPEN_DOCUMENT into app storage.
     * Returns false when the source cannot be read or the file is not a font
     * Typeface can decode.
     */
    public static boolean importFont(Context context, Uri uri)
    {
        File temp = null;
        try
        {
            String displayName = queryDisplayName(context, uri);
            String extension = extensionOf(displayName);
            File dir = getFontDir(context);
            temp = new File(dir, "custom_font.tmp");
            if (temp.exists() && !temp.delete())
                Logger.e("Cannot replace temporary font file");

            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 OutputStream output = new FileOutputStream(temp))
            {
                if (input == null)
                    return false;
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1)
                    output.write(buffer, 0, read);
                output.flush();
            }

            // Validate the file before making it the active font.
            Typeface.createFromFile(temp);

            File[] oldFiles = dir.listFiles();
            if (oldFiles != null)
            {
                for (File old : oldFiles)
                {
                    String name = old.getName().toLowerCase(Locale.ROOT);
                    if (old.isFile() && name.startsWith("custom_font.") && !name.endsWith(".tmp"))
                        //noinspection ResultOfMethodCallIgnored
                        old.delete();
                }
            }

            File target = new File(dir, "custom_font" + extension);
            if (temp.renameTo(target))
                temp = null;
            else
            {
                target = temp;
                temp = null;
            }

            Config.setCustomFontPath(target.getAbsolutePath());
            Config.setCustomFontName(displayName == null || displayName.isEmpty() ? target.getName() : displayName);
            Config.setCustomFontEnabled(true);
            invalidate();
            return true;
        }
        catch (Throwable t)
        {
            Logger.e("Cannot import custom font: " + t.getMessage());
            if (temp != null && temp.exists())
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            return false;
        }
    }

    public static void reset(Context context)
    {
        Config.setCustomFontPath("");
        Config.setCustomFontName("");
        Config.setCustomFontEnabled(false);
        invalidate();
        try
        {
            File dir = getFontDir(context);
            File[] files = dir.listFiles();
            if (files == null)
                return;
            for (File file : files)
            {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (file.isFile() && name.startsWith("custom_font."))
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    public static Context getAppContext()
    {
        try
        {
            ClassLoader loader = Utils.GetClassLoader();
            if (loader != null)
            {
                Class<?> mainApplication = Class.forName("com.zing.zalo.MainApplication", false, loader);
                Object context = mainApplication.getMethod("getAppContext").invoke(null);
                if (context instanceof Context)
                    return (Context)context;
            }
        }
        catch (Throwable ignored) { }

        try
        {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            if (app instanceof Context)
                return (Context)app;
        }
        catch (Throwable ignored) { }
        return null;
    }

    private static String queryDisplayName(Context context, Uri uri)
    {
        Cursor cursor = null;
        try
        {
            cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst())
            {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0)
                    return cursor.getString(index);
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
        finally
        {
            if (cursor != null)
                cursor.close();
        }
        String path = uri.getLastPathSegment();
        return path != null ? new File(path).getName() : "";
    }

    private static String extensionOf(String displayName)
    {
        String name = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".otf"))
            return ".otf";
        if (name.endsWith(".ttc"))
            return ".ttc";
        return ".ttf";
    }
}
