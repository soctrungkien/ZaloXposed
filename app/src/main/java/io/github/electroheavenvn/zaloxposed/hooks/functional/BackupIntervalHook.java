package io.github.electroheavenvn.zaloxposed.hooks.functional;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Overrides Zalo's native message-backup interval getter.
 *
 * Only values requested with the account-specific
 * {@code SERVER_CONFIG_SYNC_MESSAGE_INTERVAL_} key are replaced, so unrelated
 * server configuration lookups keep their original value.
 */
public class BackupIntervalHook extends BaseHook
{
    private static final String INTERVAL_KEY_PREFIX = "SERVER_CONFIG_SYNC_MESSAGE_INTERVAL_";
    private static final long MILLIS_PER_HOUR = 3_600_000L;

    @Override
    public void hook() throws Throwable
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .declaredClass("u40.p0")
                .name("Y")
                .modifiers(Modifier.PUBLIC | Modifier.STATIC)
                .returnType("long")
                .paramCount(3)
                .paramTypes("long", "boolean", "java.lang.String")
            ));
        if (methods.isEmpty())
        {
            // Fall back to the exact signature if Zalo renames the holder class.
            methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .modifiers(Modifier.PUBLIC | Modifier.STATIC)
                    .returnType("long")
                    .paramCount(3)
                    .paramTypes("long", "boolean", "java.lang.String")
                ));
        }
        if (methods.isEmpty())
        {
            Logger.e("Backup interval getter not found");
            return;
        }
        for (MethodData methodData : methods)
        {
            Method method = methodData.getMethodInstance(classLoader);
            Logger.i("Hooking: " + method);
            module.hook(method).intercept(chain ->
            {
                if (!Config.getEnableBackupInterval())
                    return chain.proceed();
                Object keyArg = chain.getArg(2);
                if (keyArg instanceof String && ((String)keyArg).startsWith(INTERVAL_KEY_PREFIX))
                {
                    return Config.getBackupIntervalHours() * MILLIS_PER_HOUR;
                }
                return chain.proceed();
            });
        }
    }
}
