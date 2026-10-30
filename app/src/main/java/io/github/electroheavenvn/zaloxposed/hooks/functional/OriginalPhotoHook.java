package io.github.electroheavenvn.zaloxposed.hooks.functional;

import android.os.Bundle;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Prefers Zalo's native "Original" photo quality on picker and external-share
 * routes. The runtime version cannot rewrite the picker's saved default state,
 * but it forces the quality getter and the outgoing Bundle flags Zalo already
 * understands.
 */
public class OriginalPhotoHook extends BaseHook
{
    private static final String MEDIA_ITEM = "com.zing.zalo.data.mediapicker.model.MediaItem";
    private static final int ORIGINAL_QUALITY = 2;
    private static final String[] BUNDLE_KEYS = new String[]{
        "bol_extra_photo_original",
        "bol_extra_photo_hd",
        "flag_share_multi_photo"
    };

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getPreferOriginalPhoto())
        {
            Logger.i("Original photo quality disabled");
            return;
        }

        hookQualityGetter();
        hookRememberedQuality();
        hookBundleFlags();
    }

    private void hookQualityGetter()
    {
        try
        {
            Class<?> mediaItem = Class.forName(MEDIA_ITEM, false, classLoader);
            Method getter = mediaItem.getDeclaredMethod("getCurrentSelectedQuality");
            if (getter.getReturnType() != int.class)
                return;
            getter.setAccessible(true);
            module.hook(getter).intercept(chain ->
            {
                if (!Config.getPreferOriginalPhoto())
                    return chain.proceed();
                return ORIGINAL_QUALITY;
            });
            Logger.i("Hooking original photo getter: " + getter);
        }
        catch (Throwable t)
        {
            Logger.e("Original photo getter unavailable: " + t);
        }
    }

    private void hookRememberedQuality() throws Throwable
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .addUsingString("LAST_SELECTION_MEDIA_QUALITY_", StringMatchType.Equals)
                .returnType("int")
                .paramCount(0)
            ));
        for (MethodData methodData : methods)
        {
            try
            {
                Method method = methodData.getMethodInstance(classLoader);
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                {
                    if (!Config.getPreferOriginalPhoto())
                        return chain.proceed();
                    return ORIGINAL_QUALITY;
                });
                Logger.i("Hooking remembered photo quality: " + method);
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }
    }

    private void hookBundleFlags() throws Throwable
    {
        for (String key : BUNDLE_KEYS)
        {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .addUsingString(key, StringMatchType.Equals)
                ));
            for (MethodData methodData : methods)
            {
                try
                {
                    Method method = methodData.getMethodInstance(classLoader);
                    int bundleIndex = bundleParameterIndex(method);
                    if (bundleIndex < 0)
                        continue;
                    method.setAccessible(true);
                    module.hook(method).intercept(chain ->
                    {
                        if (!Config.getPreferOriginalPhoto())
                            return chain.proceed();
                        Object value = chain.getArg(bundleIndex);
                        if (!(value instanceof Bundle))
                            return chain.proceed();
                        Object[] args = chain.getArgs().toArray();
                        Bundle bundle = new Bundle((Bundle)value);
                        bundle.putBoolean("bol_extra_photo_original", true);
                        bundle.putBoolean("bol_extra_photo_hd", true);
                        bundle.putBoolean("flag_share_multi_photo", true);
                        args[bundleIndex] = bundle;
                        return chain.proceedWith(args);
                    });
                    Logger.i("Hooking original photo bundle: " + method);
                }
                catch (Throwable t)
                {
                    Logger.e(t);
                }
            }
        }
    }

    private static int bundleParameterIndex(Method method)
    {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++)
        {
            if (types[i] == Bundle.class)
                return i;
        }
        return -1;
    }
}
