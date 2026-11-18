package io.github.electroheavenvn.zaloxposed.hooks.functional;

import android.content.res.Resources;

import java.lang.reflect.Method;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.HookUtils;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.ThemeColors;

/**
 * Recolors Zalo's stock blue accents at runtime.
 *
 * <p>Every {@code Resources.getColor(...)} result equal to one of the five
 * hardcoded Zalo blues is replaced with the accent selected in settings
 * (including Monet). The original resource table is never modified.</p>
 */
public class ThemeColorHook extends BaseHook
{
    @Override
    public void hook() throws Throwable
    {
        int hooked = 0;
        for (Method method : Resources.class.getDeclaredMethods())
        {
            if (!"getColor".equals(method.getName()))
                continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length < 1 || params[0] != int.class || params.length > 2)
                continue;
            try
            {
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                {
                    Object result = chain.proceed();
                    if (!(result instanceof Integer))
                        return result;
                    int color = (Integer)result;
                    for (int blue : ThemeColors.STOCK_BLUES)
                    {
                        if (color == blue)
                            return ThemeColors.getAccent(HookUtils.currentApplication());
                    }
                    return result;
                });
                hooked++;
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }
        Logger.i("Theme color hooks installed: " + hooked);
    }
}
