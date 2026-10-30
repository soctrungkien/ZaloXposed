package io.github.electroheavenvn.zaloxposed.hooks.functional;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.HookUtils;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Best-effort runtime MicroG-RE redirection.
 *
 * <p>Stock Zalo hard-codes the Google account type and the Play Services package in
 * its Drive/backup plumbing. A bytecode patch can rewrite those literals directly;
 * at runtime the same effect is reached by redirecting the Android account and
 * service boundaries Zalo already calls. This keeps the feature useful without
 * shipping a patched APK, but it is intentionally fail-open: when MicroG is not
 * installed nothing is rewritten.</p>
 */
public class MicroGSupportHook extends BaseHook
{
    public static final String MICROG_PACKAGE = "app.revanced.android.gms";
    public static final String MICROG_ACCOUNT_TYPE = "app.revanced";

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getEnableMicroGSupport())
        {
            Logger.i("MicroG support disabled");
            return;
        }
        if (!isMicroGInstalled())
        {
            Logger.e("MicroG support enabled but " + MICROG_PACKAGE + " is not installed");
            return;
        }

        hookAccountManager();
        hookServiceBinding();
        hookAccountPickerIntents();
    }

    private boolean isMicroGInstalled()
    {
        Context context = HookUtils.currentContext();
        if (context == null)
            return false;
        try
        {
            context.getPackageManager().getPackageInfo(MICROG_PACKAGE, 0);
            return true;
        }
        catch (PackageManager.NameNotFoundException ignored)
        {
            return false;
        }
    }

    private void hookAccountManager() throws Exception
    {
        int count = 0;
        for (Method method : AccountManager.class.getDeclaredMethods())
        {
            String name = method.getName();
            if ("getAccountsByType".equals(name) && method.getParameterTypes().length == 1
                && method.getParameterTypes()[0] == String.class)
            {
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                {
                    Object type = chain.getArg(0);
                    if (!Config.getEnableMicroGSupport() || !"com.google".equals(type))
                        return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    args[0] = MICROG_ACCOUNT_TYPE;
                    return chain.proceedWith(args);
                });
                count++;
            }
            else if ("getAccountsByTypeForPackage".equals(name)
                && method.getParameterTypes().length == 2
                && method.getParameterTypes()[0] == String.class)
            {
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                {
                    Object type = chain.getArg(0);
                    if (!Config.getEnableMicroGSupport() || !"com.google".equals(type))
                        return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    args[0] = MICROG_ACCOUNT_TYPE;
                    return chain.proceedWith(args);
                });
                count++;
            }
            else if ("newChooseAccountIntent".equals(name) && Modifier.isStatic(method.getModifiers()))
            {
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                {
                    if (!Config.getEnableMicroGSupport())
                        return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    for (int i = 0; i < args.length; i++)
                    {
                        if (args[i] instanceof String[])
                        {
                            args[i] = new String[]{MICROG_ACCOUNT_TYPE};
                            break;
                        }
                    }
                    return chain.proceedWith(args);
                });
                count++;
            }
            else if (("getAuthToken".equals(name) || "blockingGetAuthToken".equals(name))
                && method.getParameterTypes().length > 0
                && method.getParameterTypes()[0] == Account.class)
            {
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                {
                    Object account = chain.getArg(0);
                    if (!Config.getEnableMicroGSupport() || !(account instanceof Account)
                        || !"com.google".equals(((Account)account).type))
                        return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    args[0] = new Account(((Account)account).name, MICROG_ACCOUNT_TYPE);
                    return chain.proceedWith(args);
                });
                count++;
            }
        }
        Logger.i("MicroG account hooks installed: " + count);
    }

    private void hookServiceBinding() throws Exception
    {
        Method bindService = ContextWrapper.class.getDeclaredMethod(
            "bindService", Intent.class, ServiceConnection.class, int.class);
        module.hook(bindService).intercept(chain ->
        {
            Object intent = chain.getArg(0);
            if (Config.getEnableMicroGSupport() && intent instanceof Intent)
            {
                Object[] args = chain.getArgs().toArray();
                args[0] = remapGooglePackage((Intent)intent);
                return chain.proceedWith(args);
            }
            return chain.proceed();
        });

        try
        {
            Method bindServiceAsUser = ContextWrapper.class.getDeclaredMethod(
                "bindServiceAsUser", Intent.class, ServiceConnection.class, int.class,
                android.os.UserHandle.class);
            module.hook(bindServiceAsUser).intercept(chain ->
            {
                Object intent = chain.getArg(0);
                if (Config.getEnableMicroGSupport() && intent instanceof Intent)
                {
                    Object[] args = chain.getArgs().toArray();
                    args[0] = remapGooglePackage((Intent)intent);
                    return chain.proceedWith(args);
                }
                return chain.proceed();
            });
        }
        catch (NoSuchMethodException ignored) { }

        try
        {
            Method startService = ContextWrapper.class.getDeclaredMethod(
                "startService", Intent.class);
            module.hook(startService).intercept(chain ->
            {
                Object intent = chain.getArg(0);
                if (Config.getEnableMicroGSupport() && intent instanceof Intent)
                {
                    Object[] args = chain.getArgs().toArray();
                    args[0] = remapGooglePackage((Intent)intent);
                    return chain.proceedWith(args);
                }
                return chain.proceed();
            });
        }
        catch (NoSuchMethodException ignored) { }
    }

    private static Intent remapGooglePackage(Intent source)
    {
        ComponentName component = source.getComponent();
        if (component != null && "com.google.android.gms".equals(component.getPackageName()))
        {
            Intent copy = new Intent(source);
            copy.setComponent(new ComponentName(MICROG_PACKAGE, component.getClassName()));
            return copy;
        }
        if ("com.google.android.gms".equals(source.getPackage()))
        {
            Intent copy = new Intent(source);
            copy.setPackage(MICROG_PACKAGE);
            return copy;
        }
        return source;
    }

    private void hookAccountPickerIntents() throws Exception
    {
        Method startForResult = Activity.class.getDeclaredMethod(
            "startActivityForResult", Intent.class, int.class);
        module.hook(startForResult).intercept(chain ->
        {
            Object intent = chain.getArg(0);
            if (Config.getEnableMicroGSupport() && intent instanceof Intent)
            {
                Object[] args = chain.getArgs().toArray();
                args[0] = remapAccountPicker((Intent)intent);
                return chain.proceedWith(args);
            }
            return chain.proceed();
        });

        Method startActivity = Activity.class.getDeclaredMethod("startActivity", Intent.class);
        module.hook(startActivity).intercept(chain ->
        {
            Object intent = chain.getArg(0);
            if (Config.getEnableMicroGSupport() && intent instanceof Intent)
            {
                Object[] args = chain.getArgs().toArray();
                args[0] = remapAccountPicker((Intent)intent);
                return chain.proceedWith(args);
            }
            return chain.proceed();
        });
    }

    private static Intent remapAccountPicker(Intent source)
    {
        if (!source.hasExtra("accountTypes")
            && !source.hasExtra(AccountManager.KEY_ACCOUNT_TYPE))
            return source;
        Intent copy = new Intent(source);
        String[] types = copy.getStringArrayExtra("accountTypes");
        if (types != null && types.length > 0)
        {
            for (String type : types)
            {
                if ("com.google".equals(type))
                {
                    copy.putExtra("accountTypes",
                        new String[]{MICROG_ACCOUNT_TYPE});
                    break;
                }
            }
        }
        if ("com.google".equals(copy.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)))
            copy.putExtra(AccountManager.KEY_ACCOUNT_TYPE, MICROG_ACCOUNT_TYPE);
        return copy;
    }
}
