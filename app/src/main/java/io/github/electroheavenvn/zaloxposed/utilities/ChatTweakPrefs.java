package io.github.electroheavenvn.zaloxposed.utilities;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Small per-conversation preference store used by the chat UI tweaks.
 * Data lives in its own file next to the other module data and is never
 * written into the shared configuration file.
 */
public final class ChatTweakPrefs
{
    private ChatTweakPrefs() { }

    public static final String KEY_LOCKED = "locked";
    public static final String KEY_HIGHLIGHTED = "highlighted";
    public static final String KEY_NO_PREVIEW = "no_preview";
    public static final String KEY_NO_AVATAR = "no_avatar";
    public static final String KEY_CUSTOM_NAME = "custom_name";

    private static final String FILE_NAME = "conversation_tweaks.json";
    private static JSONObject root = new JSONObject();
    private static boolean loaded = false;

    private static synchronized void ensureLoaded()
    {
        if (loaded)
            return;
        loaded = true;
        try
        {
            String dir = Utils.GetZaloXposedDir();
            if (dir == null || dir.isEmpty())
                return;
            File file = new File(dir, FILE_NAME);
            if (!file.exists())
                return;
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null)
                content.append(line);
            reader.close();
            root = new JSONObject(content.toString());
        }
        catch (Throwable t)
        {
            Logger.e(t);
            root = new JSONObject();
        }
    }

    private static synchronized void save()
    {
        try
        {
            String dir = Utils.GetZaloXposedDir();
            if (dir == null || dir.isEmpty())
                return;
            File file = new File(dir, FILE_NAME);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();
            FileWriter writer = new FileWriter(file);
            writer.write(root.toString());
            writer.close();
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private static JSONObject bucket(String key, boolean create)
    {
        ensureLoaded();
        JSONObject value = root.optJSONObject(key);
        if (value == null && create)
        {
            value = new JSONObject();
            try { root.put(key, value); } catch (Throwable ignored) { }
        }
        return value;
    }

    public static synchronized boolean has(String key, String conversationId)
    {
        if (key == null || conversationId == null || conversationId.isEmpty())
            return false;
        JSONObject value = bucket(key, false);
        return value != null && value.optBoolean(conversationId, false);
    }

    /** Adds or removes the conversation and returns the new state. */
    public static synchronized boolean toggle(String key, String conversationId)
    {
        if (key == null || conversationId == null || conversationId.isEmpty())
            return false;
        JSONObject value = bucket(key, true);
        if (value == null)
            return false;
        boolean next = !value.optBoolean(conversationId, false);
        try { value.put(conversationId, next); } catch (Throwable ignored) { }
        save();
        return next;
    }

    public static synchronized String get(String key, String conversationId)
    {
        if (key == null || conversationId == null || conversationId.isEmpty())
            return null;
        JSONObject value = bucket(key, false);
        if (value == null)
            return null;
        String result = value.optString(conversationId, "");
        return result.isEmpty() ? null : result;
    }

    public static synchronized void set(String key, String conversationId, String value)
    {
        if (key == null || conversationId == null || conversationId.isEmpty())
            return;
        JSONObject target = bucket(key, true);
        if (target == null)
            return;
        try
        {
            if (value == null || value.trim().isEmpty())
                target.remove(conversationId);
            else
                target.put(conversationId, value.trim());
        }
        catch (Throwable ignored) { }
        save();
    }
}
