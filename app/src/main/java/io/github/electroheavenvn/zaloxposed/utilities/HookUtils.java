package io.github.electroheavenvn.zaloxposed.utilities;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Locale;

/** Small shared helpers for the runtime feature ports. */
public final class HookUtils
{
    private HookUtils() { }

    public static Application currentApplication()
    {
        try
        {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object value = currentApplication.invoke(null);
            if (value instanceof Application)
                return (Application)value;
        }
        catch (Throwable ignored) { }
        try
        {
            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            Method getInitialApplication = appGlobals.getDeclaredMethod("getInitialApplication");
            getInitialApplication.setAccessible(true);
            Object value = getInitialApplication.invoke(null);
            if (value instanceof Application)
                return (Application)value;
        }
        catch (Throwable ignored) { }
        return null;
    }

    public static Context currentContext()
    {
        Application application = currentApplication();
        if (application != null)
            return application;
        try
        {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = activityThread.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            Object thread = currentActivityThread.invoke(null);
            if (thread != null)
            {
                Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
                getSystemContext.setAccessible(true);
                Object context = getSystemContext.invoke(thread);
                if (context instanceof Context)
                    return (Context)context;
            }
        }
        catch (Throwable ignored) { }
        return null;
    }

    public static Context resolveContext(Object host)
    {
        if (host instanceof Context)
            return (Context)host;
        if (host != null)
        {
            Class<?> current = host.getClass();
            while (current != null && current != Object.class)
            {
                for (Method method : current.getDeclaredMethods())
                {
                    if (method.getParameterTypes().length != 0 || method.getReturnType() != Context.class)
                        continue;
                    try
                    {
                        method.setAccessible(true);
                        Object value = method.invoke(host);
                        if (value instanceof Context)
                            return (Context)value;
                    }
                    catch (Throwable ignored) { }
                }
                current = current.getSuperclass();
            }
        }
        return currentApplication();
    }

    public static Bundle copyBundle(Bundle source)
    {
        return source == null ? null : new Bundle(source);
    }

    public static boolean isWebUrl(String url)
    {
        if (url == null)
            return false;
        String lower = url.trim().toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    public static boolean isZaloOwnedUrl(String url)
    {
        if (!isWebUrl(url))
            return false;
        try
        {
            String host = new URI(url.trim()).getHost();
            if (host == null)
                return false;
            String lower = host.toLowerCase(Locale.US);
            return "zalo.me".equals(lower) || lower.endsWith(".zalo.me");
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    public static String safeName(String value)
    {
        if (value == null || value.isEmpty())
            return "unknown";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
