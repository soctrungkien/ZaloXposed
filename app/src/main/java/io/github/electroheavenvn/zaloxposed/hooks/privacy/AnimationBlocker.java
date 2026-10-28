package io.github.electroheavenvn.zaloxposed.hooks.privacy;

import java.io.File;

import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.Utils;

/**
 * Blocks Zalo's cached message animations and sticker keyframe animations.
 *
 * Message effects are downloaded into cache/zalo/animation. Replacing that
 * directory with a regular file prevents Zalo from recreating it, which stops
 * the effects from being loaded. Sticker packs keep their keyframes as
 * kf.json files; those are moved to .bak while blocking is enabled.
 */
public final class AnimationBlocker
{
    private AnimationBlocker() { }

    public static void apply(boolean enabled)
    {
        try
        {
            File cache = zaloCacheDir();
            if (cache == null)
                return;
            File animation = new File(cache, "animation");
            if (enabled)
            {
                //noinspection ResultOfMethodCallIgnored
                cache.mkdirs();
                if (animation.isDirectory())
                    deleteRecursive(animation);
                if (!animation.exists())
                    //noinspection ResultOfMethodCallIgnored
                    animation.createNewFile();
            }
            else if (animation.isFile())
            {
                //noinspection ResultOfMethodCallIgnored
                animation.delete();
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    public static void applySticker(boolean enabled)
    {
        try
        {
            String external = Utils.GetExternalFilesDir();
            if (external.isEmpty())
                return;
            File sticker = new File(external, "zalo/semicache/sticker");
            //noinspection ResultOfMethodCallIgnored
            sticker.mkdirs();
            toggleEntry(sticker, "23337", enabled);
            toggleEntry(sticker, "23338", enabled);
            toggleEntry(sticker, "23339", enabled);
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private static File zaloCacheDir()
    {
        String external = Utils.GetExternalFilesDir();
        if (external.isEmpty())
            return null;
        File parent = new File(external).getParentFile();
        if (parent == null)
            return null;
        return new File(parent, "cache/zalo");
    }

    private static void deleteRecursive(File file)
    {
        if (file.isDirectory())
        {
            File[] children = file.listFiles();
            if (children != null)
            {
                for (File child : children)
                    deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void toggleEntry(File root, String name, boolean enabled)
    {
        File entry = new File(root, name);
        if (entry.isFile())
            //noinspection ResultOfMethodCallIgnored
            entry.delete();
        if (!entry.isDirectory())
            return;

        File fullscreen = new File(entry, "fullscreen");
        if (fullscreen.isFile())
            //noinspection ResultOfMethodCallIgnored
            fullscreen.delete();

        File[] children = entry.listFiles();
        if (children == null)
            return;

        for (File child : children)
        {
            String childName = child.getName();
            if (enabled)
            {
                if (!childName.endsWith("kf.json") || child.length() <= 0)
                    continue;
                File backup = new File(entry, childName + ".bak");
                if (backup.exists())
                    //noinspection ResultOfMethodCallIgnored
                    backup.delete();
                if (!child.renameTo(backup))
                    continue;
                try
                {
                    //noinspection ResultOfMethodCallIgnored
                    child.createNewFile();
                }
                catch (Throwable ignored) { }
            }
            else
            {
                if (!childName.endsWith("kf.json.bak"))
                    continue;
                String originalName = childName.substring(0, childName.length() - 4);
                File original = new File(entry, originalName);
                if (original.exists() && original.length() > 0)
                {
                    // Zalo downloaded a fresh keyframe file; keep it and drop
                    // the stale backup instead of overwriting the new asset.
                    //noinspection ResultOfMethodCallIgnored
                    child.delete();
                    continue;
                }
                if (original.exists())
                    //noinspection ResultOfMethodCallIgnored
                    original.delete();
                //noinspection ResultOfMethodCallIgnored
                child.renameTo(original);
            }
        }
    }
}
