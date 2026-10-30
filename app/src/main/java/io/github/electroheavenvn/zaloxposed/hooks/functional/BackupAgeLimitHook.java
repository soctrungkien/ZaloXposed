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
 * Removes Zalo's local Drive media-backup age filter.
 *
 * <p>The stock code reads {@code BACKUP_MEDIA_LIMIT_TIME_DAY} and converts the
 * value into a cutoff timestamp. Returning zero from the reader makes every
 * timestamp eligible. This does not recreate files missing from Drive and does
 * not bypass server retention.</p>
 */
public class BackupAgeLimitHook extends BaseHook
{
    private static final String CONFIG_KEY = "BACKUP_MEDIA_LIMIT_TIME_DAY";

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getRemoveBackupAgeLimit())
        {
            Logger.i("Drive backup age limit removal disabled");
            return;
        }

        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .addUsingString(CONFIG_KEY, StringMatchType.Equals)
            ));
        if (methods.isEmpty())
        {
            Logger.e("Drive backup age-limit config reader not found");
            return;
        }

        int hooked = 0;
        for (MethodData methodData : methods)
        {
            try
            {
                Method method = methodData.getMethodInstance(classLoader);
                Class<?> returnType = method.getReturnType();
                if (returnType != int.class && returnType != long.class
                    && returnType != Integer.class && returnType != Long.class)
                    continue;
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                {
                    if (!Config.getRemoveBackupAgeLimit())
                        return chain.proceed();
                    return returnType == long.class || returnType == Long.class ? 0L : 0;
                });
                Logger.i("Hooking Drive age reader: " + method);
                hooked++;
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }
        Logger.i("Drive backup age-limit hooks installed: " + hooked);
    }
}
