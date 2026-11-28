package io.github.electroheavenvn.zaloxposed.hooks.functional;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Removes Zalo's pin-count ceilings and the community-ownership quota gates.
 * The quota values are replaced in memory only; no account data is changed.
 */
public class UnlimitedPinHook extends BaseHook
{
    private static final int UNLIMITED = 9999;
    private static final String PIN_PREF_KEY = "MAX_ITEM_PIN_PREF";
    private static final String PIN_OTHER_KEY = "features@pin_setting@maxPinOther";
    private static final String QUOTA_OWNER = "rn.n";
    private static final String QUOTA_CONTAINER = "rn.a0";
    private static final String QUOTA_PREFIX_SOURCE = "ib.j9";

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getEnableUnlimitedPin() || module == null || bridge == null)
            return;

        hookKeyOverride("u40.p0", "X", 3, 1, PIN_PREF_KEY);
        hookKeyOverride("vj0.m", "f", 2, 0, PIN_OTHER_KEY);
        forceInt("lb.be", "b", 0);
        forceTrue("lb.be", "e", 0);
        forceTrue("ib.c9", "e", 1);
        hookQuotaContainers();
    }

    /** Replaces a config getter's result when the requested key matches. */
    private void hookKeyOverride(String owner, String name, int paramCount, int keyIndex, String key)
    {
        try
        {
            for (Method method : findMethods(owner, name, paramCount))
            {
                doHook(method, chain ->
                {
                    if (!Config.getEnableUnlimitedPin())
                        return chain.proceed();
                    Object requested = chain.getArg(keyIndex);
                    if (key.equals(requested))
                        return UNLIMITED;
                    return chain.proceed();
                });
                Logger.i("Pin limit overridden: " + method);
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private void forceInt(String owner, String name, int paramCount)
    {
        try
        {
            for (Method method : findMethods(owner, name, paramCount))
            {
                if (method.getReturnType() != int.class && method.getReturnType() != Integer.class)
                    continue;
                doHook(method, chain -> Config.getEnableUnlimitedPin() ? UNLIMITED : chain.proceed());
                Logger.i("Community quota overridden: " + method);
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private void forceTrue(String owner, String name, int paramCount)
    {
        try
        {
            for (Method method : findMethods(owner, name, paramCount))
            {
                if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class)
                    continue;
                doHook(method, chain -> Config.getEnableUnlimitedPin() ? Boolean.TRUE : chain.proceed());
                Logger.i("Community ownership gate overridden: " + method);
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    /** Expands the in-memory community quota maps after they are populated. */
    private void hookQuotaContainers()
    {
        try
        {
            for (Method method : findMethods(QUOTA_CONTAINER, "b", 1))
            {
                if (method.getReturnType() != void.class)
                    continue;
                doHook(method, chain ->
                {
                    Object result = chain.proceed();
                    if (Config.getEnableUnlimitedPin())
                        expandQuotas(chain.getThisObject());
                    return result;
                });
                Logger.i("Community quota container hooked: " + method);
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
        try
        {
            Class<?> quotaClass = Class.forName(QUOTA_OWNER, false, classLoader);
            for (Constructor<?> constructor : quotaClass.getDeclaredConstructors())
            {
                if (constructor.getParameterTypes().length != 0)
                    continue;
                doHook(constructor, chain ->
                {
                    Object result = chain.proceed();
                    if (Config.getEnableUnlimitedPin())
                        expandQuotas(chain.getThisObject());
                    return result;
                });
                Logger.i("Community quota state hooked: " + constructor);
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    @SuppressWarnings("unchecked")
    private void expandQuotas(Object object)
    {
        if (object == null)
            return;
        try
        {
            Object quotaState = field(object, "h");
            applyMaps(quotaState != null ? quotaState : object);
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyMaps(Object quotaState)
    {
        if (quotaState == null)
            return;
        try
        {
            Object enableValue = field(quotaState, "b");
            Object quotaValue = field(quotaState, "c");
            if (!(enableValue instanceof Map) || !(quotaValue instanceof Map))
                return;
            Map<Object, Object> enable = (Map<Object, Object>)enableValue;
            Map<Object, Object> quota = (Map<Object, Object>)quotaValue;
            String prefix = quotaPrefix();
            if (prefix != null && !prefix.isEmpty())
                putQuota(enable, quota, prefix);
            for (int i = 0; i <= 15; i++)
                putQuota(enable, quota, Integer.toString(i));
            Collection<Object> existing = new ArrayList<>(quota.keySet());
            for (Object key : existing)
                putQuota(enable, quota, key);
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private void putQuota(Map<Object, Object> enable, Map<Object, Object> quota, Object key)
    {
        enable.put(key, 1);
        quota.put(key, UNLIMITED);
    }

    private String quotaPrefix()
    {
        try
        {
            Class<?> holder = Class.forName(QUOTA_PREFIX_SOURCE, false, classLoader);
            Object value = holder.getMethod("f").invoke(null);
            return value == null ? null : String.valueOf(value);
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static Object field(Object target, String name)
    {
        Class<?> type = target == null ? null : target.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            }
            catch (Throwable ignored)
            {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    /** Locates methods through DexKit, with a reflection fallback. */
    private List<Method> findMethods(String owner, String name, int paramCount)
    {
        List<Method> result = new ArrayList<>();
        try
        {
            List<org.luckypray.dexkit.result.MethodData> found = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .declaredClass(owner)
                    .name(name)
                    .paramCount(paramCount)
                ));
            for (org.luckypray.dexkit.result.MethodData data : found)
            {
                try
                {
                    result.add(data.getMethodInstance(classLoader));
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
        if (!result.isEmpty())
            return result;
        try
        {
            Class<?> clazz = Class.forName(owner, false, classLoader);
            for (Method method : clazz.getDeclaredMethods())
            {
                if (method.getName().equals(name) && method.getParameterTypes().length == paramCount)
                    result.add(method);
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
        return result;
    }
}
