package io.github.electroheavenvn.zaloxposed.hooks.functional;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;

import java.io.File;
import java.lang.reflect.Method;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.Utils;

/**
 * Custom notification sound ported from {@code CustomSoundsPatch}.
 *
 * <p>Drop a WAV/MP3 at {@code ZaloXposed/sounds/notification.wav} inside the
 * module data directory. While the setting is on, Zalo's default notification
 * ringtone is replaced with that file. No new ContentProvider is needed
 * because the file lives in Zalo's own app-specific storage.</p>
 */
public class CustomSoundsHook extends BaseHook
{
    @Override
    public void hook() throws Throwable
    {
        if (!Config.getCustomSounds())
            return;
        File sound = new File(Utils.GetZaloXposedDir(), "sounds/notification.wav");
        if (!sound.isFile())
        {
            Logger.i("Custom sound file not found: " + sound.getAbsolutePath());
            return;
        }
        Uri custom = Uri.fromFile(sound);

        for (Method method : RingtoneManager.class.getDeclaredMethods())
        {
            if (!"getDefaultUri".equals(method.getName()))
                continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != int.class)
                continue;
            try
            {
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                    Config.getCustomSounds() ? custom : chain.proceed());
                Logger.i("Hooking default ringtone: " + method);
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }

        try
        {
            Method setDataSource = MediaPlayer.class.getDeclaredMethod(
                "setDataSource", Context.class, Uri.class);
            setDataSource.setAccessible(true);
            module.hook(setDataSource).intercept(chain ->
            {
                if (!Config.getCustomSounds())
                    return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                Object uri = args.length > 1 ? args[1] : null;
                if (uri instanceof Uri)
                {
                    String value = uri.toString();
                    if (value.contains("notification") || value.contains("system"))
                        args[1] = custom;
                }
                return chain.proceedWith(args);
            });
            Logger.i("Hooking MediaPlayer ringtone data source");
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }
}
