package io.github.electroheavenvn.zaloxposed.hooks.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Hides the ZCloud banner in the message list through the stable
 * {@link LayoutInflater#inflate} boundary. Only the {@code messageslist} layout is
 * inspected; every other inflation costs one integer comparison.
 */
public class ZcloudBannerHook extends BaseHook
{
    private static final String TARGET_PACKAGE = "com.zing.zalo";
    private static final String MESSAGES_LIST_LAYOUT = "messageslist";
    private static final String BANNER_CONTAINER_ID = "fixed_banner_container";

    private static volatile boolean idsResolved;
    private static volatile int messagesListId;
    private static volatile int bannerContainerId;

    @Override
    public void hook() throws Throwable
    {
        int hooked = 0;
        for (Method method : LayoutInflater.class.getDeclaredMethods())
        {
            if (!"inflate".equals(method.getName()))
                continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length < 2 || types[0] != int.class
                || !View.class.isAssignableFrom(method.getReturnType()))
                continue;
            method.setAccessible(true);
            module.hook(method).intercept(chain ->
            {
                Object result = chain.proceed();
                if (!Config.getHideZCloudBanner() || !(result instanceof View))
                    return result;
                Object layoutArg = chain.getArg(0);
                if (!(layoutArg instanceof Integer))
                    return result;
                View root = (View)result;
                ensureIds(root);
                if (messagesListId == 0 || bannerContainerId == 0
                    || (Integer)layoutArg != messagesListId)
                    return result;
                View banner = root.findViewById(bannerContainerId);
                if (banner != null && banner.getVisibility() != View.GONE)
                {
                    banner.setVisibility(View.GONE);
                    ViewGroup.LayoutParams params = banner.getLayoutParams();
                    if (params != null)
                    {
                        params.height = 0;
                        banner.setLayoutParams(params);
                    }
                }
                return result;
            });
            hooked++;
        }
        Logger.i("ZCloud banner hooks installed: " + hooked);
    }

    private static void ensureIds(View root)
    {
        if (idsResolved)
            return;
        synchronized (ZcloudBannerHook.class)
        {
            if (idsResolved)
                return;
            try
            {
                messagesListId = root.getResources().getIdentifier(
                    MESSAGES_LIST_LAYOUT, "layout", TARGET_PACKAGE);
                bannerContainerId = root.getResources().getIdentifier(
                    BANNER_CONTAINER_ID, "id", TARGET_PACKAGE);
            }
            catch (Throwable ignored) { }
            idsResolved = messagesListId != 0 && bannerContainerId != 0;
        }
    }
}
