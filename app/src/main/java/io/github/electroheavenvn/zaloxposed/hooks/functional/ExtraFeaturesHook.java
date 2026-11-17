package io.github.electroheavenvn.zaloxposed.hooks.functional;

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
 * Extra privacy/notification tweaks ported from the {@code readonly} patches.
 *
 * <p>Every hook is gated by its own config key and fails open: when a Zalo
 * update changes an anchor, the hook simply installs nothing.</p>
 */
public class ExtraFeaturesHook extends BaseHook
{
    @Override
    public void hook() throws Throwable
    {
        if (Config.getStrangerNotifications())
            hookReturnTrue("features@notification_config@out_app_noti@show_for_stranger_box");
        if (Config.getHideDeliveryReceipts())
            hookAcknowledgement(102, 202, false);
        if (Config.getHideReadReceipts())
            hookAcknowledgement(10309, 205, true);
        if (Config.getHideOfficialAccounts())
            hookOfficialAccounts();
        if (Config.getViewOnlinePrivately())
            hookOnlinePrivacy();
        if (Config.getEnableDriveMediaBackup())
            hookDriveMediaBackup();
    }

    /** Forces a boolean gate method to return true. */
    private void hookReturnTrue(String literal)
    {
        try
        {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("boolean")
                    .addUsingString(literal, StringMatchType.Equals)));
            for (MethodData data : methods)
            {
                try
                {
                    Method method = data.getMethodInstance(classLoader);
                    module.hook(method).intercept(chain ->
                        Config.getStrangerNotifications() ? Boolean.TRUE : chain.proceed());
                    Logger.i("Hooking stranger notification gate: " + method);
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
    }

    /**
     * Drops outgoing acknowledgement packets. {@code delivery} selects the
     * delivered-receipt anchors, otherwise the read/seen anchors are used.
     */
    private void hookAcknowledgement(int first, int second, boolean delivery)
    {
        try
        {
            MethodMatcher matcher = MethodMatcher.create()
                .addUsingString("ackMsgList", StringMatchType.Equals)
                .addUsingString(delivery ? "data" : "seen", StringMatchType.Equals)
                .addUsingNumber(first)
                .addUsingNumber(second);
            List<MethodData> methods = bridge.findMethod(FindMethod.create().matcher(matcher));
            for (MethodData data : methods)
            {
                try
                {
                    Method method = data.getMethodInstance(classLoader);
                    module.hook(method).intercept(chain ->
                    {
                        boolean enabled = delivery
                            ? Config.getHideDeliveryReceipts()
                            : Config.getHideReadReceipts();
                        return enabled ? null : chain.proceed();
                    });
                    Logger.i("Hooking acknowledgement: " + method);
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
    }

    /** Filters Official Account rows out of the Messages list. */
    @SuppressWarnings("unchecked")
    private void hookOfficialAccounts()
    {
        try
        {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .addUsingString("LatestMention", StringMatchType.Equals)
                    .addUsingString("listFilter", StringMatchType.Equals)
                    .addUsingNumber(45)));
            for (MethodData data : methods)
            {
                try
                {
                    Method method = data.getMethodInstance(classLoader);
                    module.hook(method).intercept(chain ->
                    {
                        Object result = chain.proceed();
                        if (!Config.getHideOfficialAccounts() || !(result instanceof List))
                            return result;
                        List<Object> list = (List<Object>)result;
                        list.removeIf(item ->
                        {
                            if (item == null)
                                return false;
                            String type = item.getClass().getName();
                            return type.contains("OfficialAccount")
                                || String.valueOf(item).toLowerCase().contains("officialaccount");
                        });
                        return result;
                    });
                    Logger.i("Hooking official accounts: " + method);
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
    }

    /** Forces the {@code ENABLE_BACKUP_MEDIA} config flag on before Zalo reads it. */
    private void hookDriveMediaBackup()
    {
        try
        {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .addUsingString("ENABLE_BACKUP_MEDIA", StringMatchType.Equals)
                    .paramTypes("java.lang.String", "boolean", "boolean")));
            for (MethodData data : methods)
            {
                try
                {
                    Method method = data.getMethodInstance(classLoader);
                    module.hook(method).intercept(chain ->
                    {
                        if (!Config.getEnableDriveMediaBackup())
                            return chain.proceed();
                        Object[] args = chain.getArgs().toArray();
                        if (args.length > 1 && args[1] instanceof Boolean)
                            args[1] = Boolean.TRUE;
                        return chain.proceedWith(args);
                    });
                    Logger.i("Hooking Drive media backup flag: " + method);
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
    }

    /**
     * Keeps the user's own online state private: blocks the native state
     * broadcast and forces the "show online status" privacy setting off.
     */
    private void hookOnlinePrivacy()
    {
        try
        {
            List<MethodData> stateMethods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .name("nativeChangedStateApplication")
                    .modifiers(Modifier.NATIVE)));
            for (MethodData data : stateMethods)
            {
                try
                {
                    Method method = data.getMethodInstance(classLoader);
                    module.hook(method).intercept(chain ->
                        Config.getViewOnlinePrivately() ? null : chain.proceed());
                    Logger.i("Hooking online state broadcast: " + method);
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

        try
        {
            List<MethodData> setters = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .addUsingString("PRIVACY_SETTINGS_SHOW_ONLINE_STATUS_", StringMatchType.Equals)
                    .paramTypes("int", "int")));
            for (MethodData data : setters)
            {
                try
                {
                    Method method = data.getMethodInstance(classLoader);
                    module.hook(method).intercept(chain ->
                    {
                        if (!Config.getViewOnlinePrivately())
                            return chain.proceed();
                        Object[] args = chain.getArgs().toArray();
                        if (args.length > 0 && args[0] instanceof Integer)
                            args[0] = 0;
                        return chain.proceedWith(args);
                    });
                    Logger.i("Hooking online privacy setting: " + method);
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
    }
}
