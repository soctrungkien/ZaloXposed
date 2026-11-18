package io.github.electroheavenvn.zaloxposed.hooks.functional;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.Utils;

/**
 * Additional fine-grained features extracted from the remaining {@code readonly}
 * patches. Every hook is independently gated and fails open.
 */
public class MoreFeaturesHook extends BaseHook
{
    @Override
    public void hook() throws Throwable
    {
        if (Config.getDisableAdGates())
        {
            forceBoolean("social@story@story_ads@enable", false);
            forceBoolean("community.community_ads.enable", false);
        }
        if (Config.getHideLastSeen())
            hookLastSeen();
        if (Config.getFilterOnlineList())
            hookOnlineList();
        if (Config.getMarkRecalled())
            hookRecalledLabel();
    }

    private void forceBoolean(String literal, boolean value)
    {
        try
        {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("boolean")
                    .addUsingString(literal, StringMatchType.Equals)));
            for (MethodData data : methods)
            {
                try
                {
                    Method method = data.getMethodInstance(classLoader);
                    module.hook(method).intercept(chain ->
                        Config.getDisableAdGates() ? value : chain.proceed());
                    Logger.i("Hooking ad gate: " + method);
                }
                catch (Throwable t)
                {
                    Logger.e(t);
                }
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    /** Forces last-seen parsers to return an empty timestamp. */
    private void hookLastSeen()
    {
        try
        {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .addUsingString("timestamp", StringMatchType.Equals)
                    .addUsingString("settingOnlineStatus", StringMatchType.Equals)));
            for (MethodData data : methods)
            {
                try
                {
                    Method method = data.getMethodInstance(classLoader);
                    module.hook(method).intercept(chain ->
                    {
                        if (!Config.getHideLastSeen())
                            return chain.proceed();
                        Object result = chain.proceed();
                        if (result instanceof Number)
                            return 0L;
                        if (result instanceof String)
                            return "";
                        return result;
                    });
                    Logger.i("Hooking last seen: " + method);
                }
                catch (Throwable t)
                {
                    Logger.e(t);
                }
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    /** Clears online-member collections returned by Zalo's parsers. */
    @SuppressWarnings("unchecked")
    private void hookOnlineList()
    {
        try
        {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .addUsingString("onlines", StringMatchType.Equals)
                    .addUsingString("userId", StringMatchType.Equals)));
            for (MethodData data : methods)
            {
                try
                {
                    Method method = data.getMethodInstance(classLoader);
                    module.hook(method).intercept(chain ->
                    {
                        Object result = chain.proceed();
                        if (Config.getFilterOnlineList() && result instanceof Collection)
                            ((Collection<Object>)result).clear();
                        return result;
                    });
                    Logger.i("Hooking online list: " + method);
                }
                catch (Throwable t)
                {
                    Logger.e(t);
                }
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    /** Appends a recalled label to rows that retain a recalled message. */
    private void hookRecalledLabel()
    {
        try
        {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .name("getCurrentTimeText")
                    .returnType("java.lang.String")
                    .paramCount(0)));
            for (MethodData data : methods)
            {
                try
                {
                    Method method = data.getMethodInstance(classLoader);
                    module.hook(method).intercept(chain ->
                    {
                        Object result = chain.proceed();
                        if (!Config.getMarkRecalled() || !(result instanceof String))
                            return result;
                        if (!isRecalled(chain.getThisObject()))
                            return result;
                        String value = (String)result;
                        String label = Utils.IsEnglish() ? " · Recalled" : " · Đã thu hồi";
                        return value.contains(label) ? value : value + label;
                    });
                    Logger.i("Hooking recalled label: " + method);
                }
                catch (Throwable t)
                {
                    Logger.e(t);
                }
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private static boolean isRecalled(Object row)
    {
        if (row == null)
            return false;
        try
        {
            for (Field field : Utils.GetAllFields(row.getClass()))
            {
                if (field.getType().isPrimitive())
                    continue;
                field.setAccessible(true);
                Object value = field.get(row);
                if (value == null)
                    continue;
                String text = String.valueOf(value).toLowerCase();
                if (text.contains("msgactionundo") || text.contains("recall")
                    || text.contains("undo"))
                    return true;
            }
        }
        catch (Throwable ignored) { }
        return false;
    }
}
