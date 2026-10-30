package io.github.electroheavenvn.zaloxposed.hooks.functional;

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
 * Extends Zalo's app-wide passcode-lock grace period.
 *
 * <p>The host reads {@code SaveActiveTimePasscodeSetting} through a shared
 * integer preference reader. Replacing only that key's result keeps every other
 * preference lookup native.</p>
 */
public class PasscodeGraceHook extends BaseHook
{
    private static final String PREF_KEY = "SaveActiveTimePasscodeSetting";

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getPasscodeGrace())
        {
            Logger.i("Passcode grace disabled");
            return;
        }

        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .returnType("int")
                .paramCount(3)
                .paramTypes("int", "java.lang.String", "boolean")
                .addUsingString(PREF_KEY, StringMatchType.Equals)
            ));
        if (methods.isEmpty())
        {
            methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("int")
                    .addUsingString(PREF_KEY, StringMatchType.Equals)
                ));
        }
        if (methods.isEmpty())
        {
            Logger.e("Passcode preference reader not found");
            return;
        }

        int hooked = 0;
        for (MethodData methodData : methods)
        {
            try
            {
                Method method = methodData.getMethodInstance(classLoader);
                int keyIndex = stringParameterIndex(method);
                if (keyIndex < 0)
                    continue;
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                {
                    Object result = chain.proceed();
                    if (!Config.getPasscodeGrace())
                        return result;
                    Object key = chain.getArg(keyIndex);
                    if (!(key instanceof String) || !PREF_KEY.equals(key))
                        return result;
                    if (!(result instanceof Number))
                        return result;
                    return Config.getPasscodeGraceMs();
                });
                Logger.i("Hooking passcode grace reader: " + method);
                hooked++;
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }
        Logger.i("Passcode grace hooks installed: " + hooked);
    }

    private static int stringParameterIndex(Method method)
    {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++)
        {
            if (types[i] == String.class)
                return i;
        }
        return -1;
    }
}
