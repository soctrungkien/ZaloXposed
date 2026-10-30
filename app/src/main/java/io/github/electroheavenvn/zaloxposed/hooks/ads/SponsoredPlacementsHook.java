package io.github.electroheavenvn.zaloxposed.hooks.ads;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Overrides only the two keyed Story/community sponsored-placement gates in
 * Zalo's remote-config integer reader.
 */
public class SponsoredPlacementsHook extends BaseHook
{
    private static final String STORY_KEY = "social@story@story_ads@enable";
    private static final String COMMUNITY_KEY = "community.community_ads.enable";

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getDisableSponsoredPlacements())
        {
            Logger.i("Sponsored placement blocking disabled");
            return;
        }

        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .modifiers(Modifier.PUBLIC | Modifier.STATIC)
                .returnType("int")
                .paramCount(2)
                .paramTypes("java.lang.String", "int")
            ));
        int hooked = 0;
        for (MethodData methodData : methods)
        {
            try
            {
                Method method = methodData.getMethodInstance(classLoader);
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                {
                    Object result = chain.proceed();
                    if (!Config.getDisableSponsoredPlacements())
                        return result;
                    Object key = chain.getArg(0);
                    if (STORY_KEY.equals(key) || COMMUNITY_KEY.equals(key))
                        return 0;
                    return result;
                });
                hooked++;
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }
        Logger.i("Sponsored placement hooks installed: " + hooked);
    }
}
