package io.github.electroheavenvn.zaloxposed.hooks.privacy;

import java.io.File;

import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.Utils;

/**
 * Keeps Zalo's punch-glass sticker assets disabled while the sticker-effect
 * blocker is enabled. The check runs on a small daemon thread because Zalo can
 * refresh its sticker cache at any time after startup.
 */
public final class PunchGlassBlocker implements Runnable
{
    private static final String[] ASSETS = new String[]{
        "02.png",
        "punch-glass-anim.kf.json",
        "assets/glass_0.png",
        "assets/glass_0_bot.png",
        "assets/glass_0_top.png",
        "android/screen-shake.cfg",
        "ios/screen-shake.cfg"
    };

    private static boolean started;

    private PunchGlassBlocker() { }

    public static synchronized void start()
    {
        if (started)
            return;
        started = true;
        Thread thread = new Thread(new PunchGlassBlocker(), "ZaloXposed-PunchGlass");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run()
    {
        while (true)
        {
            try
            {
                if (Config.getBlockStickerEffect())
                    apply();
                else
                    restore();
                Thread.sleep(2000L);
            }
            catch (InterruptedException e)
            {
                return;
            }
            catch (Throwable t)
            {
                Logger.e(t);
                return;
            }
        }
    }

    private static File stickerDirectory()
    {
        String external = Utils.GetExternalFilesDir();
        if (external.isEmpty())
            return null;
        File directory = new File(external, "zalo/semicache/sticker/23339");
        return directory.isDirectory() ? directory : null;
    }

    private static void apply()
    {
        File directory = stickerDirectory();
        if (directory == null)
            return;
        for (String asset : ASSETS)
            renameToBackup(new File(directory, asset));
    }

    private static void restore()
    {
        File directory = stickerDirectory();
        if (directory == null)
            return;
        for (String asset : ASSETS)
            restoreBackup(new File(directory, asset));
    }

    private static void renameToBackup(File file)
    {
        try
        {
            if (!file.isFile() || file.length() <= 0)
                return;
            File backup = new File(file.getParentFile(), file.getName() + ".bak");
            if (backup.exists())
                //noinspection ResultOfMethodCallIgnored
                backup.delete();
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(backup);
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private static void restoreBackup(File file)
    {
        try
        {
            File backup = new File(file.getParentFile(), file.getName() + ".bak");
            if (!backup.isFile())
                return;
            if (file.exists() && file.length() > 0)
            {
                // A fresh asset is already present; drop the stale backup.
                //noinspection ResultOfMethodCallIgnored
                backup.delete();
                return;
            }
            if (file.exists())
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            //noinspection ResultOfMethodCallIgnored
            backup.renameTo(file);
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }
}
