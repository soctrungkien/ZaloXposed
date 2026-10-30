package io.github.electroheavenvn.zaloxposed.hooks.tracking;

import android.os.Bundle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Additional telemetry switches ported from the readonly feature set:
 * advertising-ID suppression, Firebase Analytics event suppression and
 * Crashlytics no-ops. Each switch is independent and fail-open.
 */
public class TelemetryExtrasHook extends BaseHook
{
    private static final String AD_CLIENT = "com.google.android.gms.ads.identifier.AdvertisingIdClient";
    private static final String AD_INFO = "com.google.android.gms.ads.identifier.AdvertisingIdClient$Info";
    private static final String FAKE_AD_ID = "00000000-0000-0000-0000-000000000000";

    @Override
    public void hook() throws Throwable
    {
        hookAdvertisingId();
        hookFirebaseAnalytics();
        hookCrashlytics();
    }

    private void hookAdvertisingId()
    {
        if (!Config.getDisableAdId())
            return;
        try
        {
            Class<?> client = Class.forName(AD_CLIENT, false, classLoader);
            Class<?> info = Class.forName(AD_INFO, false, classLoader);
            Constructor<?> constructor = info.getDeclaredConstructor(String.class, boolean.class);
            constructor.setAccessible(true);
            Object fakeInfo = constructor.newInstance(FAKE_AD_ID, true);

            for (Method method : client.getDeclaredMethods())
            {
                if (!"getAdvertisingIdInfo".equals(method.getName())
                    && !"getInfo".equals(method.getName()))
                    continue;
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                {
                    if (!Config.getDisableAdId())
                        return chain.proceed();
                    return fakeInfo;
                });
            }
            for (Method method : info.getDeclaredMethods())
            {
                if ("getId".equals(method.getName()) && method.getReturnType() == String.class)
                {
                    method.setAccessible(true);
                    module.hook(method).intercept(chain ->
                        Config.getDisableAdId() ? FAKE_AD_ID : chain.proceed());
                }
                else if ("isLimitAdTrackingEnabled".equals(method.getName())
                    && method.getReturnType() == boolean.class)
                {
                    method.setAccessible(true);
                    module.hook(method).intercept(chain ->
                        Config.getDisableAdId() ? true : chain.proceed());
                }
            }
            Logger.i("Advertising ID suppression installed");
        }
        catch (Throwable t)
        {
            Logger.e("Advertising ID classes unavailable: " + t);
        }
    }

    private void hookFirebaseAnalytics()
    {
        if (!Config.getDisableFirebaseAnalytics())
            return;
        try
        {
            Class<?> analytics = Class.forName(
                "com.google.firebase.analytics.FirebaseAnalytics", false, classLoader);
            int hooked = 0;
            for (Method method : analytics.getDeclaredMethods())
            {
                if (method.getReturnType() != void.class)
                    continue;
                Class<?>[] types = method.getParameterTypes();
                boolean event = types.length == 2 && types[0] == String.class
                    && types[1] == Bundle.class;
                boolean property = types.length == 2 && types[0] == String.class
                    && types[1] == String.class;
                boolean user = types.length == 1 && types[0] == String.class
                    && ("setUserId".equals(method.getName())
                    || "setUserProperty".equals(method.getName()));
                if (!event && !property && !user)
                    continue;
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                    Config.getDisableFirebaseAnalytics() ? null : chain.proceed());
                hooked++;
            }
            Logger.i("Firebase Analytics hooks installed: " + hooked);
        }
        catch (Throwable t)
        {
            Logger.e("Firebase Analytics unavailable: " + t);
        }
    }

    private void hookCrashlytics()
    {
        if (!Config.getDisableCrashlytics())
            return;
        try
        {
            Class<?> crashlytics = Class.forName(
                "com.google.firebase.crashlytics.FirebaseCrashlytics", false, classLoader);
            int hooked = 0;
            for (Method method : crashlytics.getDeclaredMethods())
            {
                String name = method.getName();
                if (!"recordException".equals(name) && !"log".equals(name)
                    && !"setCustomKey".equals(name) && !"setUserId".equals(name))
                    continue;
                if (method.getReturnType() != void.class)
                    continue;
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                    Config.getDisableCrashlytics() ? null : chain.proceed());
                hooked++;
            }
            Logger.i("Crashlytics hooks installed: " + hooked);
        }
        catch (Throwable t)
        {
            Logger.e("Crashlytics unavailable: " + t);
        }
    }
}
