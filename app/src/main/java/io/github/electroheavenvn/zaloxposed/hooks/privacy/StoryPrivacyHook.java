package io.github.electroheavenvn.zaloxposed.hooks.privacy;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Skips only the two outbound story-view requests. Story UI and the local
 * watched state stay untouched, so the experience is unchanged apart from the
 * server no longer receiving the view acknowledgement.
 */
public class StoryPrivacyHook extends BaseHook
{
    private static final int SEEN_COMMAND = 1343;
    private static final int SUGGESTED_COMMAND = 1356;

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getViewStoriesAnonymously())
        {
            Logger.i("Anonymous story viewing disabled");
            return;
        }
        hookCommand(SEEN_COMMAND, false);
        hookCommand(SUGGESTED_COMMAND, true);
    }

    private void hookCommand(int command, boolean suggested) throws Throwable
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .returnType("void")
                .paramCount(suggested ? 3 : 2)
                .paramTypes(suggested
                    ? new String[]{"int", "int", "java.lang.String"}
                    : new String[]{"int", "java.lang.String"})
                .addUsingNumber(command)
                .addUsingString("storyId", StringMatchType.Equals)
            ));
        int hooked = 0;
        for (MethodData methodData : methods)
        {
            try
            {
                Method method = methodData.getMethodInstance(classLoader);
                if (Modifier.isStatic(method.getModifiers()))
                    continue;
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                {
                    if (!Config.getViewStoriesAnonymously())
                        return chain.proceed();
                    return null;
                });
                Logger.i("Hooking anonymous story request: " + method);
                hooked++;
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }
        Logger.i("Anonymous story hooks installed (" + command + "): " + hooked);
    }
}
