package io.github.electroheavenvn.zaloxposed.hooks.privacy;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.HookUtils;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Opens tapped chat/feed http(s) links in the system browser instead of Zalo's
 * in-app webview. Zalo-owned links, mini-app opens and OA H5 pages stay in-app.
 */
public class WebLinkExternalizeHook extends BaseHook
{
    private static final String ZALO_WEB_VIEW = "com.zing.zalo.ui.zviews.ZaloWebView";
    private static final String ZVIEWS_PACKAGE = "com.zing.zalo.ui.zviews.";
    private static final String KEY_SOURCE_LINK = "EXTRA_SOURCE_LINK";
    private static final String KEY_FROM_MINI_APP = "EXTRA_OPEN_FROM_MINI_APP";
    private static final String KEY_OA_H5 = "extra_oa_id_open_h5";

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getOpenLinksExternally())
        {
            Logger.i("External link opening disabled");
            return;
        }
        hookRedirect();
        hookDispatch();
    }

    private void hookRedirect()
    {
        try
        {
            Class<?> webView = Class.forName(ZALO_WEB_VIEW, false, classLoader);
            for (Method method : webView.getDeclaredMethods())
            {
                Class<?>[] types = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != Uri.class
                    || types.length != 1 || types[0] != Uri.class)
                    continue;
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                {
                    if (Config.getOpenLinksExternally())
                        return chain.getArg(0);
                    return chain.proceed();
                });
                Logger.i("Hooking web redirect: " + method);
            }
        }
        catch (Throwable t)
        {
            Logger.e("ZaloWebView redirect unavailable: " + t);
        }
    }

    private void hookDispatch() throws Throwable
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .modifiers(Modifier.PUBLIC | Modifier.STATIC)
                .returnType("void")
                .paramCount(6)
                .paramTypes(null, "java.lang.String", "android.os.Bundle",
                    "boolean", "int", null)
            ));
        int hooked = 0;
        for (MethodData methodData : methods)
        {
            try
            {
                String owner = methodData.getClassName();
                if (!owner.startsWith(ZVIEWS_PACKAGE) || ZALO_WEB_VIEW.equals(owner))
                    continue;
                Method method = methodData.getMethodInstance(classLoader);
                method.setAccessible(true);
                module.hook(method).intercept(chain ->
                {
                    if (!Config.getOpenLinksExternally())
                        return chain.proceed();
                    Object bundleArg = chain.getArg(2);
                    Object urlArg = chain.getArg(1);
                    if (!(bundleArg instanceof Bundle) || !(urlArg instanceof String))
                        return chain.proceed();
                    Bundle bundle = (Bundle)bundleArg;
                    String url = (String)urlArg;
                    if (!bundle.containsKey(KEY_SOURCE_LINK)
                        || bundle.getBoolean(KEY_FROM_MINI_APP, false)
                        || bundle.containsKey(KEY_OA_H5)
                        || !HookUtils.isWebUrl(url)
                        || HookUtils.isZaloOwnedUrl(url))
                        return chain.proceed();
                    Context context = HookUtils.resolveContext(chain.getArg(0));
                    if (context == null)
                        return chain.proceed();
                    try
                    {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                        return null;
                    }
                    catch (ActivityNotFoundException missing)
                    {
                        return chain.proceed();
                    }
                    catch (Throwable t)
                    {
                        Logger.e(t);
                        return chain.proceed();
                    }
                });
                Logger.i("Hooking external link dispatch: " + method);
                hooked++;
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }
        Logger.i("External link hooks installed: " + hooked);
    }
}
