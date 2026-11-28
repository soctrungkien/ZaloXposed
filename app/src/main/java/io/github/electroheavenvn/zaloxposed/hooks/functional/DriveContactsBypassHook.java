package io.github.electroheavenvn.zaloxposed.hooks.functional;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Keeps Drive photo restore account selection usable when the Contacts
 * permission is not granted. Every gate is forced only while the feature is
 * enabled; failures simply leave the original Zalo behaviour in place.
 */
public class DriveContactsBypassHook extends BaseHook
{
    private static final int CONTACTS_REQUEST_CODE = 0x96;
    private static final int BACKUP_PERMISSION_MODE = 0x3;

    private static final String RESTORE_VIEW = "com.zing.zalo.ui.backuprestore.drive.SyncGoogleAccountMediaRestoreView";
    private static final String MANAGE_VIEW = "com.zing.zalo.ui.backuprestore.drive.ManageGoogleAccountView";
    private static final String CONTACTS_GATE = "xo1.b1";
    private static final String LOCAL_PERMISSION_CHECK = "y520";
    private static final String[] CLICK_HANDLERS = { "zb1.l", "pf1.t" };

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getEnableDriveContactsBypass() || module == null || bridge == null)
            return;

        // The shared Contacts gate and the local Drive permission check both
        // answer a boolean. Forcing them true is enough for the remaining
        // restore flow to continue without the permission.
        forceTrue(CONTACTS_GATE, "c0", 1);
        forceTrue(LOCAL_PERMISSION_CHECK, "a", 1);

        hookAccountLookup();
        hookPermissionCallbacks();
        hookSignInClick();
    }

    /** Redirects account lookups to the account picker. */
    private void hookAccountLookup()
    {
        String[][] targets = {
            { RESTORE_VIEW, "F6", "x6" },
            { RESTORE_VIEW, "U6", "M6" }
        };
        for (String[] target : targets)
        {
            try
            {
                for (Method method : findMethods(target[0], target[1], 1))
                {
                    if (method.getParameterTypes()[0] != String.class)
                        continue;
                    final String picker = target[2];
                    doHook(method, chain ->
                    {
                        if (!Config.getEnableDriveContactsBypass())
                            return chain.proceed();
                        Object view = chain.getThisObject();
                        Object account = chain.getArg(0);
                        if (view != null && account instanceof String)
                        {
                            if (invokeFirst(view, new String[] { picker, "x6", "M6" }, String.class, account))
                                return null;
                        }
                        return chain.proceed();
                    });
                    Logger.i("Drive account lookup redirected: " + method);
                }
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }
    }

    /** Continues the Drive flow after the Contacts permission callback. */
    private void hookPermissionCallbacks()
    {
        String[] callbacks = { "h5", "y5", "w5" };
        for (String viewClass : new String[] { RESTORE_VIEW, MANAGE_VIEW })
        {
            for (String callback : callbacks)
            {
                try
                {
                    for (Method method : findMethods(viewClass, callback, 3))
                    {
                        Class<?>[] types = method.getParameterTypes();
                        if (types[0] != int.class || types[1] != String[].class || types[2] != int[].class)
                            continue;
                        final boolean manage = MANAGE_VIEW.equals(viewClass);
                        doHook(method, chain ->
                        {
                            if (!Config.getEnableDriveContactsBypass())
                                return chain.proceed();
                            Object code = chain.getArg(0);
                            if (!(code instanceof Integer) || ((Integer)code) != CONTACTS_REQUEST_CODE)
                                return chain.proceed();
                            Object view = chain.getThisObject();
                            String account = firstStringField(view, manage
                                ? new String[] { "u1", "n2" }
                                : new String[] { "h1", "b2" });
                            if (view == null || account == null || account.isEmpty())
                                return chain.proceed();
                            if (manage)
                            {
                                int mode = firstIntField(view, new String[] { "v1", "o2" }, -1);
                                String[] names = mode == BACKUP_PERMISSION_MODE
                                    ? new String[] { "K6", "Z6" }
                                    : new String[] { "I6", "X6" };
                                if (invokeFirst(view, names, String.class, account))
                                    return null;
                            }
                            else if (invokeFirst(view, new String[] { "U6", "F6" }, String.class, account))
                            {
                                return null;
                            }
                            return chain.proceed();
                        });
                        Logger.i("Drive permission callback bypassed: " + method);
                    }
                }
                catch (Throwable t)
                {
                    Logger.e(t);
                }
            }
        }
    }

    /** Routes the restore Sign In button straight to account selection. */
    private void hookSignInClick()
    {
        for (String handlerClass : CLICK_HANDLERS)
        {
            try
            {
                for (Method method : findMethods(handlerClass, "run", 0))
                {
                    doHook(method, chain ->
                    {
                        if (!Config.getEnableDriveContactsBypass())
                            return chain.proceed();
                        Object handler = chain.getThisObject();
                        if (handler == null || firstIntField(handler, new String[] { "a" }, -1) != 0)
                            return chain.proceed();
                        Object view = getField(handler, "b");
                        String account = firstStringField(view, new String[] { "h1", "b2" });
                        if (view != null && account != null && !account.isEmpty()
                            && invokeFirst(view, new String[] { "U6", "F6" }, String.class, account))
                        {
                            return null;
                        }
                        return chain.proceed();
                    });
                    Logger.i("Drive sign-in gate bypassed: " + method);
                }
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }
    }

    /** Forces a one-argument boolean gate to return true. */
    private void forceTrue(String owner, String name, int paramCount)
    {
        try
        {
            for (Method method : findMethods(owner, name, paramCount))
            {
                if (method.getReturnType() != boolean.class)
                    continue;
                doHook(method, chain -> Config.getEnableDriveContactsBypass() ? Boolean.TRUE : chain.proceed());
                Logger.i("Drive permission gate bypassed: " + method);
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
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

    private static Object getField(Object target, String name)
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

    private static String firstStringField(Object target, String[] names)
    {
        for (String name : names)
        {
            Object value = getField(target, name);
            if (value instanceof String && !((String)value).isEmpty())
                return (String)value;
        }
        return null;
    }

    private static int firstIntField(Object target, String[] names, int fallback)
    {
        for (String name : names)
        {
            Object value = getField(target, name);
            if (value instanceof Integer)
                return (Integer)value;
        }
        return fallback;
    }

    private static boolean invokeFirst(Object target, String[] names, Class<?> paramType, Object argument)
    {
        for (String name : names)
        {
            Class<?> type = target.getClass();
            while (type != null)
            {
                try
                {
                    Method method = type.getDeclaredMethod(name, paramType);
                    if (Modifier.isStatic(method.getModifiers()))
                        break;
                    method.setAccessible(true);
                    method.invoke(target, argument);
                    return true;
                }
                catch (Throwable ignored)
                {
                    type = type.getSuperclass();
                }
            }
        }
        return false;
    }
}
