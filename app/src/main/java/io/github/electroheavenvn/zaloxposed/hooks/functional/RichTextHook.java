package io.github.electroheavenvn.zaloxposed.hooks.functional;

import android.text.Editable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.json.JSONObject;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.richtext.FormatClipboard;
import io.github.electroheavenvn.zaloxposed.utilities.richtext.FormatParser;
import io.github.electroheavenvn.zaloxposed.utilities.richtext.FormatRun;
import io.github.electroheavenvn.zaloxposed.utilities.richtext.FormatSend;
import io.github.electroheavenvn.zaloxposed.utilities.richtext.FormatToolbar;

/**
 * Wires the rich-text formatting engine into Zalo's chat composer.
 *
 * <p>All lookups are anchor based: the obfuscated method names change between
 * releases, but the analytics strings and the stable class names do not. Every
 * step is wrapped so a missing anchor only disables this feature.
 */
public class RichTextHook extends BaseHook
{
    private static final String CHAT_INPUT_BAR = "com.zing.zalo.ui.chat.widget.inputbar.ChatInputBar";
    private static final String CHAT_VIEW = "com.zing.zalo.ui.chat.ChatView";

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getEnableRichText())
            return;
        installInputBar();
        installFlatten();
        installSend();
        installCopy();
    }

    /** Resolves a DexKit result to either a Method or a Constructor. */
    private Executable instanceOf(MethodData data) throws Exception
    {
        return data.isConstructor()
            ? data.getConstructorInstance(classLoader)
            : data.getMethodInstance(classLoader);
    }

    // ---- Toolbar ----

    private void installInputBar()
    {
        try
        {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .declaredClass(CHAT_INPUT_BAR, StringMatchType.Equals)));
            for (MethodData data : methods)
            {
                try
                {
                    Executable executable = instanceOf(data);
                    if (executable instanceof Constructor<?> constructor
                        && constructor.getParameterCount() == 5)
                    {
                        module.hook(constructor).intercept(chain ->
                        {
                            Object result = chain.proceed();
                            try
                            {
                                Object self = chain.getThisObject();
                                if (self instanceof View)
                                    FormatClipboard.setAppContext(((View)self).getContext());
                                FormatToolbar.onInputBarCreated((FrameLayout)self);
                            }
                            catch (Throwable t) { Logger.e(t); }
                            return result;
                        });
                        Logger.i("Hooking chat input bar construction for the formatting toolbar");
                    }
                    else if (executable instanceof Method method
                        && method.getName().equals("setupLayoutRightFunctions")
                        && method.getParameterCount() == 1
                        && method.getReturnType() == void.class)
                    {
                        module.hook(method).intercept(chain ->
                        {
                            Object result = chain.proceed();
                            try
                            {
                                Object[] args = chain.getArgs().toArray();
                                Object bar = chain.getThisObject();
                                if (args.length > 0 && args[0] instanceof LinearLayout
                                    && bar instanceof FrameLayout)
                                    FormatToolbar.onRightFunctions((FrameLayout)bar, (LinearLayout)args[0]);
                            }
                            catch (Throwable t) { Logger.e(t); }
                            return result;
                        });
                        Logger.i("Hooking chat input bar right functions for the formatting button");
                    }
                }
                catch (Throwable t) { Logger.e(t); }
            }
        }
        catch (Throwable t) { Logger.e(t); }
    }

    // ---- Composer flattening ----

    private void installFlatten()
    {
        try
        {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .declaredClass(CHAT_VIEW, StringMatchType.Equals)
                    .returnType("java.lang.String")
                    .addUsingString("1000310", StringMatchType.Equals)));
            for (MethodData data : methods)
            {
                try
                {
                    Method method = data.getMethodInstance(classLoader);
                    if (method.getParameterCount() < 1
                        || !Editable.class.isAssignableFrom(method.getParameterTypes()[0]))
                        continue;
                    module.hook(method).intercept(chain ->
                    {
                        if (!Config.getEnableRichText())
                            return chain.proceed();
                        Object[] args = chain.getArgs().toArray();
                        try { FormatSend.beforeFlatten((Editable)args[0]); }
                        catch (Throwable t) { Logger.e(t); }
                        Object result = chain.proceed();
                        try { FormatSend.afterFlatten((String)result); }
                        catch (Throwable t) { Logger.e(t); }
                        return result;
                    });
                    Logger.i("Hooking composer flattening to preserve formatting: " + method);
                }
                catch (Throwable t) { Logger.e(t); }
            }
        }
        catch (Throwable t) { Logger.e(t); }
    }

    // ---- Sending ----

    private void installSend()
    {
        MethodData send = null;
        String messageClass = null;
        try
        {
            List<MethodData> sends = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("void")
                    .addUsingString("ENABLE_REMINDER_FROM_MESSAGE", StringMatchType.Equals)
                    .addUsingString("1000403", StringMatchType.Equals)));
            if (!sends.isEmpty())
                send = sends.get(0);

            // The rich-text gate on the message type is the only boolean method
            // that compares against "rtf" and calls TextUtils.equals.
            List<MethodData> gates = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("boolean")
                    .addUsingString("rtf", StringMatchType.Equals)));
            for (MethodData gate : gates)
            {
                if (gate.getParamCount() != 0 || gate.getDeclaredClassName() == null)
                    continue;
                boolean textUtils = false;
                List<MethodData> invokes = gate.getInvokes();
                if (invokes != null)
                {
                    for (MethodData invoked : invokes)
                    {
                        String owner = invoked.getDeclaredClassName();
                        if (owner != null && owner.startsWith("android.text.TextUtils"))
                        {
                            textUtils = true;
                            break;
                        }
                    }
                }
                if (textUtils)
                {
                    messageClass = gate.getDeclaredClassName();
                    break;
                }
            }
        }
        catch (Throwable t) { Logger.e(t); }

        if (send == null || messageClass == null)
        {
            Logger.w("Rich text: message build/layout hook unavailable, sending plain text");
            return;
        }

        try
        {
            Method layout = null;
            List<MethodData> invokes = send.getInvokes();
            if (invokes != null)
            {
                for (MethodData invoked : invokes)
                {
                    if (messageClass.equals(invoked.getDeclaredClassName())
                        && "void".equals(invoked.getReturnTypeName())
                        && invoked.getParamCount() == 0)
                    {
                        Executable executable = instanceOf(invoked);
                        if (executable instanceof Method)
                        {
                            layout = (Method)executable;
                            break;
                        }
                    }
                }
            }
            if (layout == null)
            {
                Logger.w("Rich text: message layout method not found, sending plain text");
                return;
            }
            final Method target = layout;
            module.hook(target).intercept(chain ->
            {
                try { attachFormattedContent(chain.getThisObject()); }
                catch (Throwable t) { Logger.e(t); }
                return chain.proceed();
            });
            Logger.i("Hooking message layout to attach rich-text content: " + target);
        }
        catch (Throwable t) { Logger.e(t); }
    }

    /** Sets an rtf TextContent on the message when it matches the captured send. */
    private void attachFormattedContent(Object message) throws Exception
    {
        if (message == null || !Config.getEnableRichText())
            return;
        String text = FormatSend.peekText();
        if (text == null || !hasText(message, text))
            return;
        JSONObject content = FormatSend.buildRtfContent(text);
        if (content == null)
            return;
        Field target = findContentField(message);
        if (target == null)
        {
            Logger.w("Rich text: text content field not found on the message");
            return;
        }
        Constructor<?> constructor = target.getType().getDeclaredConstructor(JSONObject.class);
        constructor.setAccessible(true);
        Object value = constructor.newInstance(content);
        target.setAccessible(true);
        target.set(message, value);
        Logger.i("Rich text: attached formatted content to an outgoing message");
    }

    private static boolean hasText(Object message, String text) throws IllegalAccessException
    {
        for (Class<?> type = message.getClass(); type != null; type = type.getSuperclass())
        {
            for (Field field : type.getDeclaredFields())
            {
                if (field.getType() != String.class || Modifier.isStatic(field.getModifiers()))
                    continue;
                field.setAccessible(true);
                Object value = field.get(message);
                if (value instanceof String
                    && (text.equals(value) || text.trim().equals(((String)value).trim())))
                    return true;
            }
        }
        return false;
    }

    /** The field holding the plain text content before the rtf content replaces it. */
    private static Field findContentField(Object message) throws IllegalAccessException
    {
        for (Class<?> type = message.getClass(); type != null; type = type.getSuperclass())
        {
            for (Field field : type.getDeclaredFields())
            {
                if (field.getType().isPrimitive() || field.getType().isArray()
                    || Modifier.isStatic(field.getModifiers()))
                    continue;
                field.setAccessible(true);
                Object value = field.get(message);
                if (value == null)
                    continue;
                try
                {
                    value.getClass().getDeclaredConstructor(JSONObject.class);
                    return field;
                }
                catch (NoSuchMethodException ignored) { }
            }
        }
        return null;
    }

    // ---- Copy from a message bubble ----

    private void installCopy()
    {
        try
        {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("void")
                    .addUsingString("ChatDialogView", StringMatchType.Equals)));
            for (MethodData data : methods)
            {
                try
                {
                    Method method = data.getMethodInstance(classLoader);
                    if (method.getParameterCount() != 2)
                        continue;
                    module.hook(method).intercept(chain ->
                    {
                        try
                        {
                            Object[] args = chain.getArgs().toArray();
                            if (args.length > 0)
                                stashCopiedStyles(args[0]);
                        }
                        catch (Throwable t) { Logger.e(t); }
                        return chain.proceed();
                    });
                    Logger.i("Hooking message copy to preserve formatting: " + method);
                }
                catch (Throwable t) { Logger.e(t); }
            }
        }
        catch (Throwable t) { Logger.e(t); }
    }

    private static void stashCopiedStyles(Object message)
    {
        if (message == null || !Config.getEnableRichText())
            return;
        try
        {
            String params = findParams(message);
            if (params == null)
                return;
            String best = null;
            int bestCount = 0;
            for (Class<?> type = message.getClass(); type != null; type = type.getSuperclass())
            {
                for (Field field : type.getDeclaredFields())
                {
                    if (field.getType() != String.class || Modifier.isStatic(field.getModifiers()))
                        continue;
                    field.setAccessible(true);
                    Object value = field.get(message);
                    if (!(value instanceof String))
                        continue;
                    List<FormatRun> runs = FormatParser.parse(params, (String)value);
                    if (runs.size() > bestCount)
                    {
                        bestCount = runs.size();
                        best = (String)value;
                    }
                }
            }
            if (best != null)
                FormatClipboard.onMessageCopy(best, params);
        }
        catch (Throwable t) { Logger.e(t); }
    }

    private static String findParams(Object message) throws IllegalAccessException
    {
        for (Class<?> type = message.getClass(); type != null; type = type.getSuperclass())
        {
            for (Field field : type.getDeclaredFields())
            {
                if (field.getType() != String.class || Modifier.isStatic(field.getModifiers()))
                    continue;
                field.setAccessible(true);
                Object value = field.get(message);
                if (value instanceof String && ((String)value).contains("\"styles\""))
                    return (String)value;
            }
        }
        return null;
    }
}
