package io.github.electroheavenvn.zaloxposed.hooks.functional;

import org.json.JSONArray;
import org.json.JSONObject;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Removes the client-side quick-message cap and, when the internal push handlers
 * can be resolved, keeps create / edit / delete / sync local.
 *
 * <p>Everything is best effort: if an anchor changes or a reflective lookup
 * fails, the matching hook is simply not installed and Zalo behaves normally.
 */
public class QuickMessagesHook extends BaseHook
{
    private static final String COUNT_KEY = "QUICK_MESSAGE_MAX_COUNT";
    private static final String FEATURE_KEY = "QUICK_MESSAGE_FEATURE_ENABLE";

    /** Local handlers resolved for this process, or null when unavailable. */
    private static LocalHandlers handlers;

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getEnableUnlimitedQuickMessages())
            return;
        removeCountLimit();
        removeFeatureGate();
        handlers = resolveHandlers();
        installLocalRequests();
        if (handlers == null)
            Logger.w("Quick messages: limit removed; local-only create/edit/delete unavailable"
                + " (internal handlers could not be resolved)");
    }

    private Executable instanceOf(MethodData data) throws Exception
    {
        return data.isConstructor()
            ? data.getConstructorInstance(classLoader)
            : data.getMethodInstance(classLoader);
    }

    /** Limit getters, "list is full" checks and display-only readers. */
    private void removeCountLimit()
    {
        try
        {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .addUsingString(COUNT_KEY, StringMatchType.Equals)));
            for (MethodData data : methods)
            {
                try
                {
                    Executable executable = instanceOf(data);
                    if (!(executable instanceof Method))
                        continue;
                    Method method = (Method)executable;
                    Class<?> result = method.getReturnType();
                    if (result == int.class && method.getParameterCount() == 0)
                    {
                        module.hook(method).intercept(chain ->
                            Config.getEnableUnlimitedQuickMessages() ? Integer.MAX_VALUE : chain.proceed());
                        Logger.i("Hooking quick-message limit getter: " + method);
                    }
                    else if (result == boolean.class && method.getParameterCount() == 0)
                    {
                        module.hook(method).intercept(chain ->
                            Config.getEnableUnlimitedQuickMessages() ? Boolean.FALSE : chain.proceed());
                        Logger.i("Hooking quick-message full check: " + method);
                    }
                    else if (result == int.class)
                    {
                        module.hook(method).intercept(chain ->
                        {
                            Object value = chain.proceed();
                            return Config.getEnableUnlimitedQuickMessages() ? Integer.MAX_VALUE : value;
                        });
                        Logger.i("Hooking quick-message limit reader: " + method);
                    }
                }
                catch (Throwable t) { Logger.e(t); }
            }
        }
        catch (Throwable t) { Logger.e(t); }
    }

    private void removeFeatureGate()
    {
        try
        {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .addUsingString(FEATURE_KEY, StringMatchType.Equals)));
            for (MethodData data : methods)
            {
                try
                {
                    Executable executable = instanceOf(data);
                    if (!(executable instanceof Method))
                        continue;
                    Method method = (Method)executable;
                    if (method.getReturnType() != boolean.class || method.getParameterCount() != 0)
                        continue;
                    module.hook(method).intercept(chain ->
                        Config.getEnableUnlimitedQuickMessages() ? Boolean.TRUE : chain.proceed());
                    Logger.i("Hooking quick-message feature gate: " + method);
                }
                catch (Throwable t) { Logger.e(t); }
            }
        }
        catch (Throwable t) { Logger.e(t); }
    }

    // ---- Local request handling ----

    private void installLocalRequests()
    {
        if (handlers == null)
            return;
        try
        {
            List<MethodData> creates = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("void")
                    .addUsingString("attachment", StringMatchType.Equals)
                    .addUsingString("sessionId", StringMatchType.Equals)
                    .addUsingNumber(2086)));
            List<MethodData> updates = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("void")
                    .addUsingNumber(2087)));
            List<MethodData> deletes = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("void")
                    .addUsingNumber(2088)));
            List<MethodData> syncs = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("void")
                    .addUsingNumber(2085)));

            installCreate(firstWithParams(creates, String.class, String.class, Object.class));
            installUpdate(firstWithParams(updates, long.class, Object.class, Object.class));
            installDelete(firstWithParams(deletes, long.class, String.class));
            installSync(firstWithParams(syncs, long.class));
        }
        catch (Throwable t) { Logger.e(t); }
    }

    private Method firstWithParams(List<MethodData> methods, Class<?>... parameters)
    {
        for (MethodData data : methods)
        {
            try
            {
                Executable executable = instanceOf(data);
                if (!(executable instanceof Method))
                    continue;
                Method method = (Method)executable;
                Class<?>[] actual = method.getParameterTypes();
                if (actual.length != parameters.length)
                    continue;
                boolean match = true;
                for (int index = 0; index < actual.length; index++)
                {
                    if (!actual[index].equals(parameters[index]))
                    {
                        match = false;
                        break;
                    }
                }
                if (match)
                    return method;
            }
            catch (Throwable t) { Logger.e(t); }
        }
        return null;
    }

    private void installCreate(final Method method)
    {
        if (method == null)
            return;
        try
        {
            module.hook(method).intercept(chain ->
            {
                if (!Config.getEnableUnlimitedQuickMessages())
                    return chain.proceed();
                try
                {
                    Object[] args = chain.getArgs().toArray();
                    String keyword = args.length > 0 ? String.valueOf(args[0]) : "";
                    String text = args.length > 1 ? String.valueOf(args[1]) : "";
                    JSONObject message = new JSONObject();
                    message.put("title", text);
                    JSONObject item = LocalStore.item(LocalStore.nextId(), 0, keyword, message, null,
                        System.currentTimeMillis());
                    if (handlers.add(item))
                    {
                        LocalStore.save(item);
                        return null;
                    }
                }
                catch (Throwable t) { Logger.e(t); }
                return chain.proceed();
            });
            Logger.i("Hooking quick-message create for local storage: " + method);
        }
        catch (Throwable t) { Logger.e(t); }
    }

    private void installUpdate(final Method method)
    {
        if (method == null)
            return;
        try
        {
            module.hook(method).intercept(chain ->
            {
                if (!Config.getEnableUnlimitedQuickMessages())
                    return chain.proceed();
                try
                {
                    Object[] args = chain.getArgs().toArray();
                    long id = args.length > 0 && args[0] instanceof Number
                        ? ((Number)args[0]).longValue() : 0L;
                    JSONObject item = LocalStore.fromRow(args.length > 1 ? args[1] : null, id);
                    if (item != null && handlers.update(item))
                    {
                        LocalStore.save(item);
                        return null;
                    }
                }
                catch (Throwable t) { Logger.e(t); }
                return chain.proceed();
            });
            Logger.i("Hooking quick-message edit for local storage: " + method);
        }
        catch (Throwable t) { Logger.e(t); }
    }

    private void installDelete(final Method method)
    {
        if (method == null)
            return;
        try
        {
            module.hook(method).intercept(chain ->
            {
                if (!Config.getEnableUnlimitedQuickMessages())
                    return chain.proceed();
                try
                {
                    Object[] args = chain.getArgs().toArray();
                    long id = args.length > 0 && args[0] instanceof Number
                        ? ((Number)args[0]).longValue() : 0L;
                    ArrayList<Long> ids = new ArrayList<>();
                    ids.add(id);
                    if (handlers.delete(ids))
                    {
                        LocalStore.remove(id);
                        return null;
                    }
                }
                catch (Throwable t) { Logger.e(t); }
                return chain.proceed();
            });
            Logger.i("Hooking quick-message delete for local storage: " + method);
        }
        catch (Throwable t) { Logger.e(t); }
    }

    private void installSync(final Method method)
    {
        if (method == null)
            return;
        try
        {
            module.hook(method).intercept(chain ->
                Config.getEnableUnlimitedQuickMessages() && handlers.ready() ? null : chain.proceed());
            Logger.i("Hooking quick-message sync for local storage: " + method);
        }
        catch (Throwable t) { Logger.e(t); }
    }

    /** Reflection handles for the in-app push handlers that maintain the list and its DB rows. */
    private static final class LocalHandlers
    {
        private final Method add;
        private final Method edit;
        private final Method remove;
        private final Method version;
        private final Field versionField;
        private final String userId;

        private LocalHandlers(Method add, Method edit, Method remove, Method version,
                              Field versionField, String userId)
        {
            this.add = add;
            this.edit = edit;
            this.remove = remove;
            this.version = version;
            this.versionField = versionField;
            this.userId = userId;
        }

        boolean ready()
        {
            return add != null && edit != null && remove != null && version != null && versionField != null;
        }

        private Object[] head()
        {
            try
            {
                Object store = version.invoke(null);
                versionField.setAccessible(true);
                long value = versionField.getLong(store);
                return new Object[] { value, value, userId };
            }
            catch (Throwable t)
            {
                Logger.e(t);
                return null;
            }
        }

        boolean add(JSONObject item)
        {
            Object[] head = head();
            if (head == null)
                return false;
            try
            {
                add.invoke(null, head[0], head[1], head[2], item);
                return true;
            }
            catch (Throwable t) { Logger.e(t); return false; }
        }

        boolean update(JSONObject item)
        {
            Object[] head = head();
            if (head == null)
                return false;
            long id = item.optLong("id");
            try
            {
                edit.invoke(null, id, item, head[0], head[1], head[2]);
                return true;
            }
            catch (Throwable t) { Logger.e(t); return false; }
        }

        boolean delete(ArrayList<Long> ids)
        {
            Object[] head = head();
            if (head == null)
                return false;
            try
            {
                remove.invoke(null, head[0], head[1], head[2], ids);
                return true;
            }
            catch (Throwable t) { Logger.e(t); return false; }
        }

    }

    /** Finds the app's internal push handlers by their exact signatures. */
    private LocalHandlers resolveHandlers()
    {
        try
        {
            List<MethodData> adds = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("void")
                    .paramTypes("long", "long", "java.lang.String", "org.json.JSONObject")));
            List<MethodData> edits = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("void")
                    .paramTypes("long", "org.json.JSONObject", "long", "long", "java.lang.String")));
            List<MethodData> removes = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("void")
                    .paramTypes("long", "long", "java.lang.String", "java.util.ArrayList")));

            Method add = singleMethod(adds);
            Method edit = singleMethod(edits);
            Method remove = singleMethod(removes);
            if (add == null || edit == null || remove == null
                || !add.getDeclaringClass().equals(edit.getDeclaringClass())
                || !add.getDeclaringClass().equals(remove.getDeclaringClass()))
                return null;

            Method version = null;
            Field versionField = null;
            for (Method candidate : add.getDeclaringClass().getDeclaredMethods())
            {
                if (!Modifier.isStatic(candidate.getModifiers())
                    || candidate.getParameterCount() != 0
                    || candidate.getReturnType().isPrimitive())
                    continue;
                for (Field field : candidate.getReturnType().getDeclaredFields())
                {
                    if (field.getType() == long.class && !Modifier.isStatic(field.getModifiers()))
                    {
                        version = candidate;
                        versionField = field;
                        break;
                    }
                }
                if (version != null)
                    break;
            }
            String userId = "";
            try
            {
                Class<?> core = Class.forName("com.zing.zalocore.CoreUtility");
                Field field = core.getDeclaredField("i");
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null)
                    userId = value.toString();
            }
            catch (Throwable ignored) { }

            LocalHandlers result = new LocalHandlers(add, edit, remove, version, versionField, userId);
            return result.ready() ? result : null;
        }
        catch (Throwable t)
        {
            Logger.e(t);
            return null;
        }
    }

    private Method singleMethod(List<MethodData> methods)
    {
        if (methods.size() != 1)
            return null;
        try
        {
            Executable executable = instanceOf(methods.get(0));
            return executable instanceof Method ? (Method)executable : null;
        }
        catch (Throwable t)
        {
            Logger.e(t);
            return null;
        }
    }

    /** Local JSON store and payload builders, kept separate from the app's model classes. */
    private static final class LocalStore
    {
        private static long lastId;

        static synchronized long nextId()
        {
            long id = Math.max(System.currentTimeMillis(), lastId + 1);
            lastId = id;
            return id;
        }

        static JSONObject item(long id, int type, String keyword, JSONObject message,
                               JSONObject media, long created)
        {
            JSONObject item = new JSONObject();
            try
            {
                item.put("id", id);
                item.put("type", type);
                item.put("keyword", keyword == null ? "" : keyword);
                if (message != null)
                    item.put("message", message);
                if (media != null)
                    item.put("media", media);
                item.put("createdTime", created);
                item.put("lastModified", System.currentTimeMillis());
            }
            catch (Throwable t) { Logger.e(t); }
            return item;
        }

        /** Builds an update payload from the app's obfuscated row object. */
        static JSONObject fromRow(Object row, long id)
        {
            if (row == null)
                return null;
            int type = 0;
            String keyword = "";
            long created = 0;
            JSONObject message = null;
            try
            {
                for (Class<?> typeClass = row.getClass(); typeClass != null; typeClass = typeClass.getSuperclass())
                {
                    for (Field field : typeClass.getDeclaredFields())
                    {
                        if (Modifier.isStatic(field.getModifiers()))
                            continue;
                        field.setAccessible(true);
                        Object value = field.get(row);
                        Class<?> kind = field.getType();
                        if (kind == int.class && value instanceof Number)
                            type = ((Number)value).intValue();
                        else if (kind == long.class && value instanceof Number)
                            created = ((Number)value).longValue();
                        else if (kind == String.class && value != null)
                            keyword = value.toString();
                        else if (value != null && message == null)
                            message = toJson(value);
                    }
                }
            }
            catch (Throwable t) { Logger.e(t); return null; }
            return item(id, type, keyword, message, null, created > 0 ? created : System.currentTimeMillis());
        }

        private static JSONObject toJson(Object value)
        {
            for (Method method : value.getClass().getMethods())
            {
                if (method.getParameterCount() == 0 && method.getReturnType() == JSONObject.class)
                {
                    try { return (JSONObject)method.invoke(value); }
                    catch (Throwable ignored) { }
                }
            }
            return null;
        }

        private static File file()
        {
            return new File(Config.getConfigFile().getParentFile(), "quick_messages.json");
        }

        static void save(JSONObject item)
        {
            try
            {
                JSONArray items = read();
                long id = item.optLong("id");
                for (int index = 0; index < items.length(); index++)
                {
                    if (items.optJSONObject(index) != null && items.getJSONObject(index).optLong("id") == id)
                    {
                        items.put(index, item);
                        write(items);
                        return;
                    }
                }
                items.put(item);
                write(items);
            }
            catch (Throwable t) { Logger.e(t); }
        }

        static void remove(long id)
        {
            try
            {
                JSONArray items = read();
                JSONArray kept = new JSONArray();
                for (int index = 0; index < items.length(); index++)
                {
                    JSONObject item = items.optJSONObject(index);
                    if (item == null || item.optLong("id") != id)
                        kept.put(item);
                }
                write(kept);
            }
            catch (Throwable t) { Logger.e(t); }
        }

        private static JSONArray read()
        {
            File file = file();
            if (!file.exists())
                return new JSONArray();
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                    content.append(line);
                JSONArray array = new JSONArray(content.toString());
                return array;
            }
            catch (Throwable t) { return new JSONArray(); }
        }

        private static void write(JSONArray items)
        {
            try (FileWriter writer = new FileWriter(file()))
            {
                writer.write(items.toString());
            }
            catch (Throwable t) { Logger.e(t); }
        }
    }
}
