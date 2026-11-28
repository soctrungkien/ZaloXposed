package io.github.electroheavenvn.zaloxposed.hooks.functional;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.bot.BotCommandEngine;
import io.github.electroheavenvn.zaloxposed.utilities.bot.BotMessageBridge;

/**
 * Optional opt-in chat command engine. Incoming messages are observed without
 * changing them; replies are sent through a best-effort reflection bridge and
 * are rate limited by the command engine.
 */
public class BotEngineHook extends BaseHook
{
    private static final int MAX_REPLIES_PER_COMMAND = 3;
    private static final long REPLY_DELAY_MS = 150L;

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getEnableBotEngine() || module == null || bridge == null)
            return;
        BotMessageBridge.init(classLoader);
        hookChatViewCapture();
        hookIncomingPackets();
        hookIncomingCommits();
        hookOutgoingCapture();
        Logger.i("Chat command engine enabled");
    }

    /** Captures the conversation sender from the open chat view. */
    private void hookChatViewCapture()
    {
        try
        {
            Class<?> chatView = Class.forName("com.zing.zalo.ui.chat.ChatView", false, classLoader);
            int hooked = 0;
            for (Method method : chatView.getDeclaredMethods())
            {
                String name = method.getName();
                boolean lifecycle = "onAttachedToWindow".equals(name) || "onResume".equals(name)
                    || "onViewCreated".equals(name);
                if (!lifecycle || method.getParameterCount() > 1)
                    continue;
                method.setAccessible(true);
                doHook(method, chain ->
                {
                    if (Config.getEnableBotEngine() && !BotMessageBridge.isSending())
                        BotMessageBridge.captureFromView(chain.getThisObject());
                    return chain.proceed();
                });
                hooked++;
            }
            Logger.i("Chat command engine chat-view capture hooks: " + hooked);
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    /** Packet handler carries the full message payload as its fourth argument. */
    private void hookIncomingPackets()
    {
        try
        {
            List<Method> methods = resolved(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                    .returnType("void")
                    .paramCount(8)
                    .paramTypes("java.lang.String", "int", "int", "org.json.JSONObject", "int", "boolean", "long", null)
                    .addUsingString("PullMessage", StringMatchType.Equals)
                    .addUsingString("ChatPacketHandler", StringMatchType.Equals)
                ));
            for (Method method : methods)
            {
                doHook(method, chain ->
                {
                    Object result = chain.proceed();
                    if (!Config.getEnableBotEngine() || BotMessageBridge.isSending())
                        return result;
                    Object payload = chain.getArg(3);
                    if (payload instanceof JSONObject)
                        handlePayload((JSONObject)payload);
                    return result;
                });
                Logger.i("Chat command engine hooked packet handler: " + method);
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    /** Message commit path provides a second, version-independent source. */
    private void hookIncomingCommits()
    {
        try
        {
            List<Method> methods = resolved(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .declaredClass("s00.k")
                    .name("c")
                    .paramCount(2)
                    .paramTypes("java.lang.String", "t00.a")
                ));
            if (methods.isEmpty())
            {
                Class<?> holder = Class.forName("s00.k", false, classLoader);
                for (Method method : holder.getDeclaredMethods())
                {
                    if (method.getName().equals("c") && method.getParameterTypes().length == 2
                        && method.getParameterTypes()[0] == String.class)
                        methods.add(method);
                }
            }
            for (Method method : methods)
            {
                doHook(method, chain ->
                {
                    Object result = chain.proceed();
                    if (!Config.getEnableBotEngine() || BotMessageBridge.isSending())
                        return result;
                    Object thread = chain.getArg(0);
                    Object container = chain.getArg(1);
                    Object messages = field(container, "b");
                    if (messages instanceof Collection && thread instanceof String)
                    {
                        for (Object message : (Collection<?>)messages)
                            handleCommitted((String)thread, message);
                    }
                    return result;
                });
                Logger.i("Chat command engine hooked commit handler: " + method);
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    /** Captures a per-conversation sender reference used for replies. */
    private void hookOutgoingCapture()
    {
        try
        {
            List<Method> methods = resolved(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .declaredClass("fz.s0")
                    .name("q")
                    .paramCount(8)
                    .paramTypes("java.lang.String", "java.lang.String", "fz.u5", "o00.k2", "o00.v1", "boolean", "boolean", "sd1.n")
                ));
            if (methods.isEmpty())
            {
                Class<?> holder = Class.forName("fz.s0", false, classLoader);
                for (Method method : holder.getDeclaredMethods())
                {
                    Class<?>[] types = method.getParameterTypes();
                    if (method.getName().equals("q") && types.length == 8
                        && types[0] == String.class && types[1] == String.class)
                        methods.add(method);
                }
            }
            for (Method method : methods)
            {
                doHook(method, chain ->
                {
                    if (Config.getEnableBotEngine() && !BotMessageBridge.isSending())
                    {
                        try
                        {
                            BotMessageBridge.capture(chain.getThisObject());
                        }
                        catch (Throwable ignored)
                        {
                            // Capture is an optimisation; sending still fails open.
                        }
                    }
                    return chain.proceed();
                });
                Logger.i("Chat command engine hooked sender capture: " + method);
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private void handlePayload(JSONObject payload)
    {
        try
        {
            JSONArray messages = payload.optJSONArray("msg");
            if (messages == null)
                return;
            for (int i = 0; i < messages.length(); i++)
            {
                JSONObject wrapper = messages.optJSONObject(i);
                if (wrapper == null)
                    continue;
                JSONObject text = wrapper.optJSONObject("text");
                if (text == null)
                    continue;
                String type = text.optString("type", "");
                if (type.equals("chat.undo") || type.equals("chat.delete") || type.startsWith("typing"))
                    continue;
                JSONObject data = text.optJSONObject("data");
                if (data == null)
                    continue;
                String content = firstNonEmpty(data.optString("message", ""), data.optString("msg", ""));
                String sender = firstNonEmpty(idOf(data, "fromU"), idOf(data, "uidFrom"));
                if (content.isEmpty() || sender.isEmpty())
                    continue;
                long timestamp = normaliseTime(data.optLong("ts", 0L));
                String name = firstNonEmpty(data.optString("dName", ""), data.optString("fromD", ""));
                String thread = resolveThread(data);
                dispatch(new BotCommandEngine.Incoming(thread, sender, name, content, timestamp));
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private void handleCommitted(String thread, Object message)
    {
        if (message == null)
            return;
        try
        {
            if (Config.getEnableBotEngine() && !BotMessageBridge.isSending())
                BotMessageBridge.captureIfAbsent(message);
            Object senderValue = call(message, "V1");
            String sender = senderValue instanceof String ? (String)senderValue : stringField(message, "c");
            String content = stringField(message, "g");
            if (sender == null || content == null)
                return;
            long timestamp = 0L;
            Object time = call(message, "k2");
            if (!(time instanceof Number))
                time = call(message, "e2");
            if (time instanceof Number)
                timestamp = normaliseTime(((Number)time).longValue());
            dispatch(new BotCommandEngine.Incoming(thread, sender, sender, content, timestamp));
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private void dispatch(BotCommandEngine.Incoming message)
    {
        if (!Config.getEnableBotEngine() || message.threadId.isEmpty())
            return;
        try
        {
            BotCommandEngine.remember(message.threadId, message.senderId, message.senderName);
            if (!BotCommandEngine.isCommand(message))
                return;
            List<String> replies = BotCommandEngine.execute(message);
            if (replies.isEmpty())
                return;
            final List<String> limited = replies.subList(0, Math.min(replies.size(), MAX_REPLIES_PER_COMMAND));
            final Handler main = new Handler(Looper.getMainLooper());
            main.post(() ->
            {
                if (!Config.getEnableBotEngine())
                    return;
                long delay = 0L;
                for (String reply : limited)
                {
                    final String text = reply;
                    main.postDelayed(() -> BotMessageBridge.send(message.threadId, text), delay);
                    delay += REPLY_DELAY_MS;
                }
            });
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private List<Method> resolved(org.luckypray.dexkit.query.FindMethod findMethod)
    {
        List<Method> methods = new ArrayList<>();
        try
        {
            for (org.luckypray.dexkit.result.MethodData data : bridge.findMethod(findMethod))
            {
                try
                {
                    methods.add(data.getMethodInstance(classLoader));
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
        return methods;
    }

    private static long normaliseTime(long value)
    {
        if (value <= 0L)
            return 0L;
        return value < 10_000_000_000L ? value * 1000L : value;
    }

    private static String resolveThread(JSONObject data)
    {
        String group = firstNonEmpty(
            idOf(data, "gId"),
            idOf(data, "groupId"),
            idOf(data, "gOwnerId")
        );
        if (!group.isEmpty())
            return group;
        return firstNonEmpty(
            idOf(data, "idTo"),
            idOf(data, "to"),
            idOf(data, "toUid"),
            idOf(data, "ownerId")
        );
    }

    private static String idOf(JSONObject data, String key)
    {
        Object value = data.opt(key);
        return value == null || JSONObject.NULL.equals(value) ? "" : String.valueOf(value);
    }

    private static String firstNonEmpty(String... values)
    {
        for (String value : values)
        {
            if (value != null && !value.isEmpty())
                return value;
        }
        return "";
    }

    private static Object field(Object target, String name)
    {
        Class<?> type = target == null ? null : target.getClass();
        while (type != null)
        {
            try
            {
                java.lang.reflect.Field field = type.getDeclaredField(name);
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

    private static String stringField(Object target, String name)
    {
        Object value = field(target, name);
        return value instanceof String ? (String)value : null;
    }

    private static Object call(Object target, String name)
    {
        if (target == null)
            return null;
        try
        {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }
}
