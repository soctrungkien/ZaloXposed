package io.github.electroheavenvn.zaloxposed.utilities.bot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.Utils;

/**
 * Small persistent store for the optional chat command engine.
 *
 * <p>Values are plain text and live in the module's own directory. A failed
 * read or write only loses persistence; the in-memory copy keeps working.</p>
 */
public final class BotStore
{
    private static final String FILE_NAME = "bot_engine.properties";
    private static final String PREFIX_KEY = "prefix";
    private static final String CUSTOM_KEY = "custom.";
    private static final String ROLE_KEY = "role.";
    private static final String DEFAULT_PREFIX = "!";

    private static final Properties VALUES = new Properties();
    private static boolean loaded;

    private BotStore() { }

    private static synchronized void ensureLoaded()
    {
        if (loaded)
            return;
        loaded = true;
        File file = storeFile();
        if (file == null || !file.isFile())
            return;
        try (FileInputStream in = new FileInputStream(file))
        {
            VALUES.load(in);
        }
        catch (IOException | RuntimeException e)
        {
            Logger.e("Could not read bot settings", e);
        }
    }

    private static File storeFile()
    {
        String dir = Utils.GetZaloXposedDir();
        if (dir == null || dir.isEmpty())
            return null;
        return new File(dir, FILE_NAME);
    }

    private static synchronized void save()
    {
        File file = storeFile();
        if (file == null)
            return;
        try
        {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file, false))
            {
                VALUES.store(out, "chat command settings");
            }
        }
        catch (IOException | RuntimeException e)
        {
            Logger.e("Could not save bot settings", e);
        }
    }

    public static synchronized String getPrefix()
    {
        ensureLoaded();
        String value = VALUES.getProperty(PREFIX_KEY);
        if (value == null || value.isEmpty())
            return DEFAULT_PREFIX;
        return value;
    }

    public static synchronized void setPrefix(String prefix)
    {
        if (prefix == null || prefix.isEmpty())
            return;
        ensureLoaded();
        VALUES.setProperty(PREFIX_KEY, prefix);
        save();
    }

    public static synchronized String getCustom(String name)
    {
        ensureLoaded();
        if (name == null)
            return null;
        return VALUES.getProperty(CUSTOM_KEY + name.toLowerCase(Locale.ROOT));
    }

    public static synchronized Map<String, String> getCustoms()
    {
        ensureLoaded();
        Map<String, String> result = new HashMap<>();
        for (String key : VALUES.stringPropertyNames())
        {
            if (key.startsWith(CUSTOM_KEY))
                result.put(key.substring(CUSTOM_KEY.length()), VALUES.getProperty(key));
        }
        return result;
    }

    public static synchronized void setCustom(String name, String response)
    {
        if (name == null || name.isEmpty() || response == null || response.isEmpty())
            return;
        ensureLoaded();
        VALUES.setProperty(CUSTOM_KEY + name.toLowerCase(Locale.ROOT), response);
        save();
    }

    public static synchronized void clearCustoms()
    {
        ensureLoaded();
        for (String key : VALUES.stringPropertyNames())
        {
            if (key.startsWith(CUSTOM_KEY) || key.startsWith(ROLE_KEY))
                VALUES.remove(key);
        }
        save();
    }

    /** Roles are local labels only: 0 blocked, 1 member, 3 operator. */
    public static synchronized int getRole(String userId)
    {
        ensureLoaded();
        if (userId == null)
            return 1;
        try
        {
            return Integer.parseInt(VALUES.getProperty(ROLE_KEY + userId, "1"));
        }
        catch (NumberFormatException ignored)
        {
            return 1;
        }
    }

    public static synchronized void setRole(String userId, int role)
    {
        if (userId == null)
            return;
        ensureLoaded();
        VALUES.setProperty(ROLE_KEY + userId, Integer.toString(role));
        save();
    }
}
