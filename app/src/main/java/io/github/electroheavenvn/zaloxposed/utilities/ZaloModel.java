package io.github.electroheavenvn.zaloxposed.utilities;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Small reflection helpers used by the download menu and media saver.
 *
 * Zalo's models are heavily obfuscated and change between releases, so the
 * hooks locate values by type and reachability instead of hard-coded field
 * names wherever possible.
 */
public final class ZaloModel
{
    private ZaloModel() { }

    public static Iterable<Field> fields(Class<?> type)
    {
        java.util.List<Field> result = new java.util.ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class)
        {
            for (Field field : current.getDeclaredFields())
            {
                if (!Modifier.isStatic(field.getModifiers()))
                    result.add(field);
            }
            current = current.getSuperclass();
        }
        return result;
    }

    public static Object valueOfType(Object host, Class<?> type)
    {
        if (host == null)
            return null;
        for (Field field : fields(host.getClass()))
        {
            if (!type.isAssignableFrom(field.getType()))
                continue;
            try
            {
                field.setAccessible(true);
                return field.get(host);
            }
            catch (Throwable ignored) { }
        }
        return null;
    }

    public static boolean holdsInt(Object host, int value)
    {
        if (host == null)
            return false;
        for (Field field : fields(host.getClass()))
        {
            if (field.getType() != int.class)
                continue;
            try
            {
                field.setAccessible(true);
                if (field.getInt(host) == value)
                    return true;
            }
            catch (Throwable ignored) { }
        }
        return false;
    }

    public static Object reachable(Object host, Class<?> type)
    {
        if (host == null || type == null)
            return null;
        if (type.isInstance(host))
            return host;
        for (Field field : fields(host.getClass()))
        {
            if (field.getType().isPrimitive()
                || field.getType().getName().startsWith("java.")
                || field.getType().getName().startsWith("android."))
                continue;
            Object value;
            try
            {
                field.setAccessible(true);
                value = field.get(host);
            }
            catch (Throwable ignored)
            {
                continue;
            }
            if (value == null)
                continue;
            if (type.isInstance(value))
                return value;
            for (Method method : value.getClass().getMethods())
            {
                if (method.getParameterCount() != 0 || method.getReturnType() != type)
                    continue;
                try
                {
                    method.setAccessible(true);
                    Object result = method.invoke(value);
                    if (result != null)
                        return result;
                }
                catch (Throwable ignored) { }
            }
        }
        return null;
    }

    public static Object find(Object host, Class<?> type, int depth)
    {
        if (host == null || type == null)
            return null;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return find(host, type, depth, seen);
    }

    private static Object find(Object node, Class<?> type, int left, Set<Object> seen)
    {
        if (type.isInstance(node))
            return node;
        if (left == 0 || !seen.add(node))
            return null;

        for (Method method : node.getClass().getDeclaredMethods())
        {
            if (Modifier.isStatic(method.getModifiers())
                || method.getParameterCount() != 0
                || method.getReturnType() != type)
                continue;
            try
            {
                method.setAccessible(true);
                Object result = method.invoke(node);
                if (result != null)
                    return result;
            }
            catch (Throwable ignored) { }
        }

        for (Field field : fields(node.getClass()))
        {
            String name = field.getType().getName();
            if (field.getType().isPrimitive()
                || name.startsWith("java.")
                || name.startsWith("android.")
                || name.startsWith("kotlin."))
                continue;
            Object value;
            try
            {
                field.setAccessible(true);
                value = field.get(node);
            }
            catch (Throwable ignored)
            {
                continue;
            }
            if (value == null)
                continue;
            Object result = find(value, type, left - 1, seen);
            if (result != null)
                return result;
        }
        return null;
    }
}
