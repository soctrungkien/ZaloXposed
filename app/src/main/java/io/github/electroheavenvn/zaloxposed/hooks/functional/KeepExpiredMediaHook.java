package io.github.electroheavenvn.zaloxposed.hooks.functional;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Keeps on-device chat big files usable after Zalo marks them expired.
 *
 * <p>The host classifies each big file through an enum carrying
 * {@code BIG_FILE_EXPIRED} / {@code BIG_FILE_NOT_EXPIRED}. Mapping the expired
 * result back to not-expired keeps the local file openable. Missing files are
 * never recreated and no network request is made.</p>
 */
public class KeepExpiredMediaHook extends BaseHook
{
    private static final String EXPIRED = "BIG_FILE_EXPIRED";
    private static final String NOT_EXPIRED = "BIG_FILE_NOT_EXPIRED";

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getKeepExpiredMedia())
        {
            Logger.i("Keep expired media disabled");
            return;
        }

        List<ClassData> classes = bridge.findClass(FindClass.create()
            .matcher(ClassMatcher.create()
                .addFieldForName(EXPIRED)
                .addFieldForName(NOT_EXPIRED)
            ));
        if (classes.isEmpty())
        {
            Logger.e("Expired-media state enum not found");
            return;
        }

        for (ClassData classData : classes)
        {
            try
            {
                Class<?> stateClass = classData.getInstance(classLoader);
                if (!stateClass.isEnum())
                    continue;
                Object expired = enumConstant(stateClass, EXPIRED);
                Object notExpired = enumConstant(stateClass, NOT_EXPIRED);
                if (expired == null || notExpired == null)
                    continue;

                List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .returnType(stateClass.getName())
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
                            if (Config.getKeepExpiredMedia() && expired.equals(result))
                                return notExpired;
                            return result;
                        });
                        Logger.i("Hooking expired-media classifier: " + method);
                        hooked++;
                    }
                    catch (Throwable t)
                    {
                        Logger.e(t);
                    }
                }
                Logger.i("Expired-media hooks installed for " + stateClass.getName() + ": " + hooked);
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> type, String name)
    {
        try
        {
            return Enum.valueOf((Class)type, name);
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }
}
