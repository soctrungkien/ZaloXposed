package io.github.electroheavenvn.zaloxposed.hooks.ui;

import android.app.Application;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.widget.TextView;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.CustomFont;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.NativeFontObserver;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Applies the font selected in ZaloXposed settings to Zalo's typeface factory,
 * Zinstant rich text, chat text paint and message modules. A lifecycle
 * observer also refreshes already-created text views.
 */
public class CustomFontHook extends BaseHook
{
    @Override
    public void hook() throws Throwable
    {
        hookTypefaceFactory();
        hookZinstantFont();
        hookChatPaint();
        hookMessageStyle();
        hookApplicationObserver();
    }

    private void hookTypefaceFactory() throws Exception
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .modifiers(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL)
                .returnType("android.graphics.Typeface")
                .paramCount(2)
                .paramTypes("android.content.Context", "int")
                .addUsingString("fonts/Roboto-Regular.ttf", StringMatchType.Equals)
            ));
        if (methods.isEmpty())
        {
            // Some Zalo builds do not mark the factory method final.
            methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .modifiers(Modifier.PUBLIC | Modifier.STATIC)
                    .returnType("android.graphics.Typeface")
                    .paramCount(2)
                    .paramTypes("android.content.Context", "int")
                    .addUsingString("Roboto-Regular", StringMatchType.Contains)
                ));
        }
        if (methods.isEmpty())
        {
            Logger.e("Zalo typeface factory not found");
            return;
        }
        for (MethodData methodData : methods)
        {
            Method method = methodData.getMethodInstance(classLoader);
            Logger.i("Hooking: " + method);
            module.hook(method).intercept(chain ->
            {
                if (!Config.getCustomFontEnabled())
                    return chain.proceed();
                Object contextArg = chain.getArg(0);
                Object styleArg = chain.getArg(1);
                if (contextArg instanceof Context && styleArg instanceof Integer)
                {
                    Typeface typeface = CustomFont.getTypeface((Context)contextArg, (Integer)styleArg);
                    if (typeface != null)
                        return typeface;
                }
                return chain.proceed();
            });
        }
    }

    private void hookZinstantFont() throws Exception
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .modifiers(Modifier.PUBLIC | Modifier.STATIC)
                .returnType("android.graphics.Typeface")
                .paramCount(1)
                .paramTypes("com.zing.zalo.zinstant.zom.text.ZOMTextSpan")
                .addInvoke(MethodMatcher.create().name("getFontKey"))
            ));
        if (methods.isEmpty())
        {
            Logger.e("Zinstant font factory not found");
            return;
        }
        for (MethodData methodData : methods)
        {
            Method method = methodData.getMethodInstance(classLoader);
            Logger.i("Hooking: " + method);
            module.hook(method).intercept(chain ->
            {
                if (!Config.getCustomFontEnabled())
                    return chain.proceed();
                Context context = CustomFont.getAppContext();
                if (context == null)
                    return chain.proceed();
                Typeface typeface = CustomFont.getTypeface(context, 0);
                return typeface != null ? typeface : chain.proceed();
            });
        }
    }

    private void hookChatPaint() throws Exception
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                .returnType("android.text.TextPaint")
                .paramCount(0)
                .addUsingString("Init default text paint ChatContent", StringMatchType.Equals)
            ));
        if (methods.isEmpty())
        {
            methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .modifiers(Modifier.PUBLIC)
                    .returnType("android.text.TextPaint")
                    .paramCount(0)
                    .addUsingString("ChatContent", StringMatchType.Contains)
                ));
        }
        if (methods.isEmpty())
        {
            Logger.e("Chat text paint method not found");
            return;
        }
        for (MethodData methodData : methods)
        {
            Method method = methodData.getMethodInstance(classLoader);
            Logger.i("Hooking: " + method);
            module.hook(method).intercept(chain ->
            {
                Object result = chain.proceed();
                if (!Config.getCustomFontEnabled() || !(result instanceof TextPaint))
                    return result;
                Context context = findContext(chain.getThisObject());
                if (context == null)
                    context = CustomFont.getAppContext();
                if (context != null)
                    CustomFont.forChatPaint(context, (TextPaint)result);
                return result;
            });
        }
    }

    private void hookMessageStyle() throws Exception
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                .returnType("void")
                .paramCount(1)
                .paramTypes("bv1.b")
                .addInvoke(MethodMatcher.create()
                    .declaredClass("o62.b0")
                    .name("h")
                )
                .addInvoke(MethodMatcher.create()
                    .declaredClass("kn1.i")
                    .name("K0")
                )
            ));
        if (methods.isEmpty())
        {
            Logger.e("Message style typeface method not found");
            return;
        }
        for (MethodData methodData : methods)
        {
            Method method = methodData.getMethodInstance(classLoader);
            Logger.i("Hooking: " + method);
            module.hook(method).intercept(chain ->
            {
                Object result = chain.proceed();
                if (!Config.getCustomFontEnabled())
                    return result;
                Object self = chain.getThisObject();
                if (self instanceof TextView)
                {
                    TextView textView = (TextView)self;
                    Typeface custom = CustomFont.forMessageModule(textView.getContext(), textView.getTypeface());
                    if (custom != null)
                        textView.setTypeface(custom);
                }
                return result;
            });
        }
    }

    private void hookApplicationObserver() throws Exception
    {
        try
        {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            Logger.i("Hooking: " + onCreate);
            module.hook(onCreate).intercept(chain ->
            {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Application)
                    NativeFontObserver.register((Application)self);
                return result;
            });
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }

        try
        {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .declaredClass("com.zing.zalo.MainApplication")
                    .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                    .returnType("void")
                    .paramCount(0)
                    .addUsingString("CURRENT_USER_UID_FLOW_RESTORE_APP", StringMatchType.Equals)
                ));
            for (MethodData methodData : methods)
            {
                Method method = methodData.getMethodInstance(classLoader);
                Logger.i("Hooking: " + method);
                module.hook(method).intercept(chain ->
                {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof Application)
                        NativeFontObserver.register((Application)self);
                    return result;
                });
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private Context findContext(Object object)
    {
        if (object instanceof Context)
            return (Context)object;
        if (object == null)
            return null;
        Class<?> current = object.getClass();
        while (current != null && current != Object.class)
        {
            for (Field field : current.getDeclaredFields())
            {
                if (!Context.class.isAssignableFrom(field.getType()))
                    continue;
                try
                {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value instanceof Context)
                        return (Context)value;
                }
                catch (Throwable ignored) { }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
