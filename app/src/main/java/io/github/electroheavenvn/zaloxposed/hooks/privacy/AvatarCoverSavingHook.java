package io.github.electroheavenvn.zaloxposed.hooks.privacy;

import android.os.BaseBundle;
import android.os.Bundle;
import android.os.SystemClock;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;

/**
 * Restores saving and screenshots for avatars opened from a profile and for
 * profile cover photos.
 *
 * The upstream patches replace screenshot-policy instructions inside the two
 * launch helpers. At runtime the helpers are wrapped with a thread-local route
 * marker, and the Bundle screenshot flag is forced to false only while one of
 * those routes is active. The profile-cover viewer menu's download flag is
 * enabled separately.
 */
public class AvatarCoverSavingHook extends BaseHook
{
    private static final String PREVENT_SCREENSHOT_KEY = "EXTRA_SHOULD_PREVENT_SCREENSHOT";
    private static final String FROM_PROFILE_COVER_KEY = "fromProfileCover";
    private static final int ROUTE_AVATAR = 1;
    private static final int ROUTE_COVER = 2;

    private static final ThreadLocal<Integer> currentRoute = new ThreadLocal<>();
    private static volatile long lastCoverLaunch = 0L;
    private static boolean loggedCoverMenu;

    private Class<?> coverMenuModelClass;
    private Field coverMenuDownloadField;

    @Override
    public void hook() throws Throwable
    {
        resolveCoverMenuModel();
        hookScreenshotFlag();
        hookAvatarRoute();
        hookCoverRoute();
        hookCoverMenu();
    }

    private void hookScreenshotFlag()
    {
        try
        {
            Method method = BaseBundle.class.getDeclaredMethod("putBoolean", String.class, boolean.class);
            Logger.i("Hooking: " + method);
            module.hook(method).intercept(chain ->
            {
                Integer route = currentRoute.get();
                Object key = chain.getArg(0);
                Object value = chain.getArg(1);
                if (route != null
                    && PREVENT_SCREENSHOT_KEY.equals(key)
                    && Boolean.TRUE.equals(value)
                    && ((route == ROUTE_AVATAR && Config.getEnableAvatarSaving())
                        || (route == ROUTE_COVER && Config.getEnableCoverSaving())))
                {
                    return chain.proceed(new Object[]{key, false});
                }
                return chain.proceed();
            });
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private void hookAvatarRoute() throws Exception
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .declaredClass("com.zing.zalo.social.presentation.profile.friend_profile.UserProfileView")
                .name("u8")
                .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                .returnType("void")
                .paramCount(2)
                .paramTypes("java.lang.String", "java.lang.String")
                .addUsingString("avatarPhoto", StringMatchType.Equals)
            ));
        if (methods.isEmpty())
        {
            Logger.e("Avatar screenshot policy method not found");
            return;
        }
        for (MethodData methodData : methods)
        {
            Method method = methodData.getMethodInstance(classLoader);
            Logger.i("Hooking: " + method);
            module.hook(method).intercept(chain ->
            {
                if (!Config.getEnableAvatarSaving())
                    return chain.proceed();
                currentRoute.set(ROUTE_AVATAR);
                try
                {
                    return chain.proceed();
                }
                finally
                {
                    currentRoute.remove();
                }
            });
        }
    }

    private void hookCoverRoute() throws Exception
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .declaredClass("kb.q0")
                .name("K")
                .modifiers(Modifier.PUBLIC | Modifier.STATIC)
                .returnType("void")
                .paramCount(5)
                .paramTypes(
                    "qi.a",
                    "com.zing.zalo.control.ItemAlbumMobile",
                    "fz.b2",
                    "g50.r",
                    "int")
                .addUsingString(FROM_PROFILE_COVER_KEY, StringMatchType.Equals)
            ));
        if (methods.isEmpty())
        {
            Logger.e("Profile-cover screenshot policy method not found");
            return;
        }
        for (MethodData methodData : methods)
        {
            Method method = methodData.getMethodInstance(classLoader);
            Logger.i("Hooking: " + method);
            module.hook(method).intercept(chain ->
            {
                if (!Config.getEnableCoverSaving())
                    return chain.proceed();
                lastCoverLaunch = SystemClock.elapsedRealtime();
                currentRoute.set(ROUTE_COVER);
                try
                {
                    return chain.proceed();
                }
                finally
                {
                    currentRoute.remove();
                }
            });
        }
    }

    private void hookCoverMenu() throws Exception
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .declaredClass("com.zing.zalo.social.features.menu_feed.BottomSheetMenuView")
                .name("S4")
                .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                .returnType("void")
                .paramCount(1)
                .paramTypes("android.os.Bundle")
            ));
        if (methods.isEmpty())
        {
            Logger.e("Profile-cover viewer menu method not found");
            return;
        }
        for (MethodData methodData : methods)
        {
            Method method = methodData.getMethodInstance(classLoader);
            Logger.i("Hooking: " + method);
            module.hook(method).intercept(chain ->
            {
                if (Config.getEnableCoverSaving())
                {
                    try
                    {
                        Object argument = chain.getArg(0);
                        if (argument instanceof Bundle)
                            allowCoverDownload(chain.getThisObject(), (Bundle)argument);
                    }
                    catch (Throwable t)
                    {
                        Logger.e(t);
                    }
                }
                return chain.proceed();
            });
        }
    }

    private void allowCoverDownload(Object menuView, Bundle bundle)
    {
        if (bundle == null || !isCoverRoute(bundle))
            return;
        try
        {
            if (bundle.getBoolean(PREVENT_SCREENSHOT_KEY, false))
                bundle.putBoolean(PREVENT_SCREENSHOT_KEY, false);
        }
        catch (Throwable ignored) { }

        if (coverMenuModelClass == null || coverMenuDownloadField == null)
            return;
        Object model = findModel(bundle, menuView);
        if (model == null)
        {
            if (!loggedCoverMenu)
            {
                loggedCoverMenu = true;
                Logger.e("Profile-cover menu model not found in menu bundle");
            }
            return;
        }
        try
        {
            coverMenuDownloadField.setBoolean(model, true);
            lastCoverLaunch = 0L;
            if (!loggedCoverMenu)
            {
                loggedCoverMenu = true;
                Logger.i("Enabled profile-cover save action");
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private boolean isCoverRoute(Bundle bundle)
    {
        try
        {
            if (bundle.getBoolean(FROM_PROFILE_COVER_KEY, false))
                return true;
            for (String key : bundle.keySet())
            {
                if (key == null)
                    continue;
                String lower = key.toLowerCase(Locale.ROOT);
                if (!lower.contains("profilecover") && !lower.contains("profile_cover")
                    && !lower.equals("fromcover") && !lower.equals("is_cover"))
                    continue;
                Object value = bundle.get(key);
                if (value instanceof Boolean && (Boolean)value)
                    return true;
            }
        }
        catch (Throwable ignored) { }

        // The launch helper runs before the cover viewer opens; the menu can be
        // opened much later while the user is still looking at the cover.
        return lastCoverLaunch > 0L && SystemClock.elapsedRealtime() - lastCoverLaunch < 300_000L;
    }

    private Object findModel(Bundle bundle, Object menuView)
    {
        try
        {
            for (String key : bundle.keySet())
            {
                try
                {
                    Object value = bundle.get(key);
                    if (coverMenuModelClass.isInstance(value))
                        return value;
                }
                catch (Throwable ignored) { }
            }
        }
        catch (Throwable ignored) { }

        if (coverMenuModelClass.isInstance(menuView))
            return menuView;
        if (menuView == null)
            return null;
        Class<?> current = menuView.getClass();
        while (current != null && current != Object.class)
        {
            for (Field field : current.getDeclaredFields())
            {
                try
                {
                    field.setAccessible(true);
                    Object value = field.get(menuView);
                    if (coverMenuModelClass.isInstance(value))
                        return value;
                }
                catch (Throwable ignored) { }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private void resolveCoverMenuModel()
    {
        try
        {
            coverMenuModelClass = Class.forName(
                "com.zing.zalo.social.features.menu_feed.model.BottomSheetMenuBundleDataPhotoViewfull",
                false,
                classLoader);
            coverMenuDownloadField = coverMenuModelClass.getDeclaredField("j");
            coverMenuDownloadField.setAccessible(true);
        }
        catch (Throwable t)
        {
            Logger.e("Profile-cover menu model class/field not found: " + t.getMessage());
        }
    }
}
