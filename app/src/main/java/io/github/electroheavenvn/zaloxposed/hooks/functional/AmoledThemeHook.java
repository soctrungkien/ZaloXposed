package io.github.electroheavenvn.zaloxposed.hooks.functional;

import android.content.res.Resources;
import android.graphics.Color;

import java.lang.reflect.Method;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Runtime AMOLED approximation of the {@code AmoledThemePatch}: every dark
 * theme color lookup is mapped to pure black. Zalo's XML resources are not
 * modified, but all colours resolved through {@link Resources#getColor(int)}
 * (and its themed overload) become black while the toggle is on.
 */
public class AmoledThemeHook extends BaseHook
{
    @Override
    public void hook() throws Throwable
    {
        if (!Config.getAmoledTheme())
            return;
        int hooked = 0;
        for (Method method : Resources.class.getDeclaredMethods())
        {
            if (!"getColor".equals(method.getName()))
                continue;
            Class<?>[] params = method.getParameterTypes();
            if ((params.length != 1 && params.length != 2) || params[0] != int.class)
                continue;
            try
            {
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                {
                    Object result = chain.proceed();
                    if (!Config.getAmoledTheme())
                        return result;
                    try
                    {
                        Object self = chain.getThisObject();
                        int resId = (Integer)chain.getArg(0);
                        if (self instanceof Resources)
                        {
                            String name = ((Resources)self).getResourceEntryName(resId);
                            if (name.startsWith("Dark_") || "ng95".equals(name))
                                return Color.BLACK;
                        }
                    }
                    catch (Throwable ignored) { }
                    return result;
                });
                hooked++;
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }
        Logger.i("AMOLED theme hooks installed: " + hooked);
    }
}
