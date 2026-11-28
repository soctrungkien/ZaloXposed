package io.github.electroheavenvn.zaloxposed.utilities.bot;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.electroheavenvn.zaloxposed.utilities.CoreUtilityHelper;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Reflection bridge between normal Java replies and the app's message sender.
 *
 * <p>A conversation sender is captured from outgoing messages and reused for
 * replies in that conversation. If no sender was captured, sending is skipped
 * rather than guessed, so unrelated conversations are never affected.</p>
 */
public final class BotMessageBridge
{
    private static final Map<String, WeakReference<Object>> THREAD_SENDERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> SENDING = new ThreadLocal<>();
    private static volatile WeakReference<Object> fallbackSender = new WeakReference<>(null);

    private static ClassLoader loader;
    private static volatile boolean initialised;

    private BotMessageBridge() { }

    public static void init(ClassLoader classLoader)
    {
        loader = classLoader;
        initialised = classLoader != null;
    }

    public static boolean isSending()
    {
        return Boolean.TRUE.equals(SENDING.get());
    }

    /** Captures the sender object of an outgoing message for later replies. */
    public static void capture(Object sender)
    {
        if (sender == null)
            return;
        String thread = threadOfSender(sender);
        if (thread != null && !thread.isEmpty() && !"unknown".equals(thread))
            THREAD_SENDERS.put(thread, new WeakReference<>(sender));
        fallbackSender = new WeakReference<>(sender);
    }

    /** Captures a fallback sender only when no better reference is available. */
    public static void captureIfAbsent(Object sender)
    {
        if (sender == null || fallbackSender.get() != null)
            return;
        fallbackSender = new WeakReference<>(sender);
    }

    /**
     * Resolves the conversation sender from the visible chat view and captures
     * it for that thread. This lets replies work in a conversation the user has
     * opened but not yet typed in.
     */
    public static void captureFromView(Object view)
    {
        if (view == null)
            return;
        try
        {
            Object holder = field(view, "D0");
            Object delegate = field(holder, "b");
            Object sender = field(delegate, "e");
            if (sender == null)
                sender = field(view, "e");
            capture(sender);
        }
        catch (Throwable t)
        {
            Logger.e("Could not capture chat sender from view", t);
        }
    }

    private static String threadOfSender(Object sender)
    {
        Object current = field(sender, "i");
        current = field(current, "h");
        Object value = field(current, "b");
        return value == null ? null : String.valueOf(value);
    }

    /** Sends one plain-text reply. Returns false when the bridge is unavailable. */
    public static boolean send(String threadId, String text)
    {
        if (!initialised || threadId == null || threadId.isEmpty() || text == null || text.isEmpty())
            return false;
        if (Boolean.TRUE.equals(SENDING.get()))
            return false;
        SENDING.set(Boolean.TRUE);
        try
        {
            Object sender = senderFor(threadId);
            Object senderRef = field(sender, "i");
            if (senderRef == null)
                return false;
            String self = CoreUtilityHelper.GetCurrentUserID();
            if (self == null || self.isEmpty() || "0".equals(self))
                return false;

            Class<?> sessionClass = clazz("je0.e0");
            Object session = sessionClass.getMethod("d").invoke(null);
            String target = String.valueOf(sessionClass.getMethod("b").invoke(session));

            Class<?> idFactory = clazz("i10.p");
            Method createId = staticMethod(idFactory, "b", 4);
            Object messageId = createId.invoke(null, target, "", text, self);

            Class<?> idClass = clazz("com.zing.zalo.data.entity.chat.message.MessageId");
            Class<?> messageClass = clazz("o00.n");
            Constructor<?> constructor = messageClass.getDeclaredConstructor(int.class, idClass);
            constructor.setAccessible(true);
            Object message = constructor.newInstance(1, messageId);
            setField(message, "g", threadId);
            setField(message, "k", senderRef);
            setField(message, "v", 1);
            Object bus = field(sender, "h");
            if (bus != null)
                setField(message, "h", bus);

            Object built = messageClass.getMethod("a").invoke(message);
            if (built == null)
                return false;
            try
            {
                setField(built, "m1", clazz("o00.v1").getConstructor().newInstance());
            }
            catch (Throwable ignored)
            {
                // Optional message decoration; the reply can still be sent.
            }
            Method dispatch = null;
            for (Method candidate : clazz("je0.a0").getMethods())
            {
                if (candidate.getName().equals("z0") && Modifier.isStatic(candidate.getModifiers())
                    && candidate.getParameterTypes().length == 2
                    && candidate.getParameterTypes()[0] == String.class)
                {
                    dispatch = candidate;
                    break;
                }
            }
            if (dispatch == null)
                return false;
            dispatch.invoke(null, "", built);
            return true;
        }
        catch (Throwable t)
        {
            Logger.e("Could not send chat reply", t);
            return false;
        }
        finally
        {
            SENDING.remove();
        }
    }

    private static Object senderFor(String threadId)
    {
        WeakReference<Object> reference = THREAD_SENDERS.get(threadId);
        Object sender = reference == null ? null : reference.get();
        if (sender != null)
            return sender;
        // Never fall back to a sender belonging to a different conversation.
        sender = fallbackSender.get();
        String thread = threadOfSender(sender);
        return threadId.equals(thread) ? sender : null;
    }

    private static Class<?> clazz(String name) throws ClassNotFoundException
    {
        if (loader != null)
            return Class.forName(name, false, loader);
        return Class.forName(name);
    }

    private static Method staticMethod(Class<?> owner, String name, int paramCount) throws NoSuchMethodException
    {
        for (Method method : owner.getMethods())
        {
            if (method.getName().equals(name) && Modifier.isStatic(method.getModifiers())
                && method.getParameterTypes().length == paramCount)
                return method;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static Object field(Object target, String name)
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

    private static void setField(Object target, String name, Object value)
    {
        Class<?> type = target == null ? null : target.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            }
            catch (Throwable ignored)
            {
                type = type.getSuperclass();
            }
        }
    }
}
