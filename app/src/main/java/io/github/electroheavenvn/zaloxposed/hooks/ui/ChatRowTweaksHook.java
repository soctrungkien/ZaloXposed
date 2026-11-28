package io.github.electroheavenvn.zaloxposed.hooks.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.ChatTweakPrefs;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Conversation-list and conversation-view tweaks driven by the long-press menu:
 * per-chat preview/avatar cleanup, read-only lock and unread highlighting.
 *
 * Every hook is guarded by its own configuration switch and fails open.
 */
public class ChatRowTweaksHook extends BaseHook
{
    private static final String MENU_TAG = "ui_tweak_menu_done";

    private static final int ITEM_LOCK = 0x5A31;
    private static final int ITEM_HIGHLIGHT = 0x5A32;
    private static final int ITEM_PREVIEW = 0x5A33;
    private static final int ITEM_AVATAR = 0x5A34;
    private static final int ITEM_RENAME = 0x5A35;

    private volatile String activeLockedConversation;

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getEnableCleanChatList() && !Config.getEnableReadonlyChats()
            && !Config.getEnableChatHighlight())
            return;
        hookContextMenu();
        hookChatListBind();
        hookUnreadMethods();
        hookInputBar();
        hookReactionPicker();
        hookChatRowControls();
        Logger.i("Chat row tweaks installed");
    }

    // ------------------------------------------------------------------ lookup

    private Method findMethod(String className, String name, String returnType, int paramCount)
    {
        try
        {
            Class<?> clazz = Class.forName(className, false, classLoader);
            List<MethodData> data = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .declaredClass(clazz)
                    .name(name)
                    .returnType(returnType)
                    .paramCount(paramCount)));
            for (MethodData methodData : data)
            {
                try { return methodData.getMethodInstance(classLoader); }
                catch (Throwable ignored) { }
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> clazz, String name)
    {
        for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass())
        {
            try
            {
                Method method = current.getDeclaredMethod(name);
                if (method.getParameterCount() == 0)
                {
                    method.setAccessible(true);
                    return method;
                }
            }
            catch (Throwable ignored) { }
        }
        return null;
    }

    private static String conversationIdOf(Object conversation)
    {
        if (conversation == null)
            return null;
        try
        {
            Method method = findNoArgMethod(conversation.getClass(), "e");
            Object value = method == null ? null : method.invoke(conversation);
            return value instanceof String text && !text.isEmpty() ? text : null;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String name)
    {
        if (target == null)
            return null;
        try
        {
            Method method = findNoArgMethod(target.getClass(), name);
            return method == null ? null : method.invoke(target);
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static void invokeInt(Object target, String name, int value)
    {
        if (target == null)
            return;
        try
        {
            for (Class<?> current = target.getClass(); current != null && current != Object.class; current = current.getSuperclass())
            {
                for (Method method : current.getDeclaredMethods())
                {
                    if (!name.equals(method.getName()) || method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != int.class)
                        continue;
                    method.setAccessible(true);
                    method.invoke(target, value);
                    return;
                }
            }
        }
        catch (Throwable ignored) { }
    }

    private static List<Method> charSequenceSetters(Object target)
    {
        List<Method> result = new ArrayList<>();
        if (target == null)
            return result;
        for (Method method : target.getClass().getMethods())
        {
            if (method.getParameterCount() != 1 || method.getReturnType() != void.class)
                continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter == CharSequence.class || parameter == String.class)
                result.add(method);
        }
        return result;
    }

    // ------------------------------------------------------------ long-press

    private void hookContextMenu()
    {
        Class<?> menuClass;
        try
        {
            menuClass = Class.forName("com.zing.zalo.ui.chat.contextmenu.ConversationContextMenuView", false, classLoader);
        }
        catch (Throwable t)
        {
            Logger.e(t);
            return;
        }

        try
        {
            List<MethodData> data = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .declaredClass(menuClass)
                    .returnType("com.zing.zalo.zdesign.component.popover.PopoverView")
                    .paramCount(1)));
            for (MethodData methodData : data)
            {
                final Method method;
                try { method = methodData.getMethodInstance(classLoader); }
                catch (Throwable ignored) { continue; }

                doHook(method, chain ->
                {
                    Object result = chain.proceed();
                    try
                    {
                        Object self = chain.getThisObject();
                        if (result instanceof View popover && MENU_TAG.equals(popover.getTag()))
                            return result;
                        if (result instanceof View popover)
                            popover.setTag(MENU_TAG);

                        String conversationId = conversationIdOf(chain.getArg(0));
                        if (conversationId == null)
                            return result;
                        Context context = self instanceof View view ? view.getContext() : null;
                        List<Object> items = findMenuItemList(self, result);
                        if (items == null || items.isEmpty())
                            return result;
                        Class<?> itemClass = items.get(0).getClass();
                        addMenuItems(items, itemClass, context, conversationId);
                        wrapMenuListener(self, result, context, conversationId, itemClass);
                    }
                    catch (Throwable t)
                    {
                        Logger.e(t);
                    }
                    return result;
                });
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> findMenuItemList(Object... roots)
    {
        for (Object root : roots)
        {
            List<Object> found = scanForMenuList(root, 0);
            if (found != null)
                return found;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> scanForMenuList(Object root, int depth)
    {
        if (root == null || depth > 2)
            return null;
        for (Class<?> current = root.getClass(); current != null && current != Object.class; current = current.getSuperclass())
        {
            for (Field field : current.getDeclaredFields())
            {
                if (field.getType().isPrimitive())
                    continue;
                try
                {
                    field.setAccessible(true);
                    Object value = field.get(root);
                    if (value instanceof List<?> list && !list.isEmpty())
                    {
                        for (Object item : list)
                        {
                            if (item != null && findNoArgMethod(item.getClass(), "getId") != null)
                                return (List<Object>)list;
                        }
                    }
                    else if (value != null && !(value instanceof CharSequence)
                        && !(value instanceof Number) && !(value instanceof Boolean))
                    {
                        List<Object> nested = scanForMenuList(value, depth + 1);
                        if (nested != null)
                            return nested;
                    }
                }
                catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static Constructor<?> findItemConstructor(Class<?> itemClass)
    {
        for (Constructor<?> constructor : itemClass.getDeclaredConstructors())
        {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 6
                && parameters[0] == int.class
                && CharSequence.class.isAssignableFrom(parameters[2])
                && parameters[3] == boolean.class
                && parameters[5] == int.class)
            {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        return null;
    }

    private static Object normalTypeValue(Class<?> type)
    {
        if (type == null || !type.isEnum())
            return null;
        Object[] constants = type.getEnumConstants();
        if (constants.length == 0)
            return null;
        for (Object constant : constants)
        {
            if ("NORMAL".equalsIgnoreCase(((Enum<?>)constant).name()))
                return constant;
        }
        return constants[0];
    }

    private static void addMenuItems(List<Object> items, Class<?> itemClass, Context context, String conversationId)
    {
        Constructor<?> constructor = findItemConstructor(itemClass);
        if (constructor == null)
            return;
        Object normal = normalTypeValue(constructor.getParameterTypes()[4]);
        if (normal == null)
            return;
        try
        {
            if (Config.getEnableReadonlyChats())
            {
                boolean locked = ChatTweakPrefs.has(ChatTweakPrefs.KEY_LOCKED, conversationId);
                addItem(constructor, normal, items, ITEM_LOCK, locked ? "Unlock chat" : "Lock chat");
            }
            if (Config.getEnableChatHighlight())
            {
                boolean highlighted = ChatTweakPrefs.has(ChatTweakPrefs.KEY_HIGHLIGHTED, conversationId);
                addItem(constructor, normal, items, ITEM_HIGHLIGHT,
                    highlighted ? "Remove unread highlight" : "Highlight as unread");
            }
            if (Config.getEnableCleanChatList())
            {
                boolean previewHidden = ChatTweakPrefs.has(ChatTweakPrefs.KEY_NO_PREVIEW, conversationId);
                boolean avatarHidden = ChatTweakPrefs.has(ChatTweakPrefs.KEY_NO_AVATAR, conversationId);
                addItem(constructor, normal, items, ITEM_PREVIEW,
                    previewHidden ? "Show preview" : "Hide preview");
                addItem(constructor, normal, items, ITEM_AVATAR,
                    avatarHidden ? "Show avatar" : "Hide avatar");
                addItem(constructor, normal, items, ITEM_RENAME, "Rename locally");
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private static void addItem(Constructor<?> constructor, Object normal, List<Object> items, int id, String title)
        throws Exception
    {
        items.add(constructor.newInstance(id, null, title, true, normal, 0));
    }

    private static Object findListenerHolder(Object menuView, Class<?> itemClass)
    {
        for (Class<?> current = menuView.getClass(); current != null && current != Object.class; current = current.getSuperclass())
        {
            for (Field field : current.getDeclaredFields())
            {
                if (field.getType().isPrimitive() || field.getType().isArray())
                    continue;
                try
                {
                    field.setAccessible(true);
                    Object value = field.get(menuView);
                    if (value != null && holdsItemListener(value, itemClass))
                        return value;
                }
                catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static boolean holdsItemListener(Object holder, Class<?> itemClass)
    {
        for (Class<?> current = holder.getClass(); current != null && current != Object.class; current = current.getSuperclass())
        {
            for (Field field : current.getDeclaredFields())
            {
                if (!field.getType().isInterface())
                    continue;
                for (Method method : field.getType().getMethods())
                {
                    for (Class<?> parameter : method.getParameterTypes())
                    {
                        if (parameter == itemClass || parameter.isAssignableFrom(itemClass))
                            return true;
                    }
                }
            }
        }
        return false;
    }

    private static Field findListenerField(Object holder, Class<?> itemClass)
    {
        try
        {
            Field preferred = holder.getClass().getDeclaredField("h");
            if (preferred.getType().isInterface())
            {
                preferred.setAccessible(true);
                return preferred;
            }
        }
        catch (Throwable ignored) { }

        for (Class<?> current = holder.getClass(); current != null && current != Object.class; current = current.getSuperclass())
        {
            for (Field field : current.getDeclaredFields())
            {
                if (!field.getType().isInterface())
                    continue;
                for (Method method : field.getType().getMethods())
                {
                    for (Class<?> parameter : method.getParameterTypes())
                    {
                        if (parameter == itemClass || parameter.isAssignableFrom(itemClass))
                        {
                            field.setAccessible(true);
                            return field;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static void wrapMenuListener(Object menuView, Object popover, Context context,
                                         String conversationId, Class<?> itemClass)
    {
        Object holder = findListenerHolder(menuView, itemClass);
        if (holder == null)
            return;
        Field listenerField = findListenerField(holder, itemClass);
        if (listenerField == null)
            return;
        try
        {
            final Object original = listenerField.get(holder);
            if (original == null || Proxy.isProxyClass(original.getClass()))
                return;
            Class<?>[] interfaces = original.getClass().getInterfaces();
            if (interfaces.length == 0)
                interfaces = new Class<?>[] { listenerField.getType() };

            InvocationHandler handler = (proxy, method, args) ->
            {
                if (args != null)
                {
                    for (Object arg : args)
                    {
                        Object idValue = idOf(arg);
                        if (!(idValue instanceof Integer))
                            continue;
                        int id = (Integer)idValue;
                        if (id == ITEM_LOCK)
                        {
                            boolean on = ChatTweakPrefs.toggle(ChatTweakPrefs.KEY_LOCKED, conversationId);
                            toast(context, on ? "Read only enabled" : "Read only disabled");
                            dismiss(menuView, popover);
                            return null;
                        }
                        if (id == ITEM_HIGHLIGHT)
                        {
                            boolean on = ChatTweakPrefs.toggle(ChatTweakPrefs.KEY_HIGHLIGHTED, conversationId);
                            toast(context, on ? "Highlight enabled" : "Highlight disabled");
                            dismiss(menuView, popover);
                            return null;
                        }
                        if (id == ITEM_PREVIEW)
                        {
                            boolean on = ChatTweakPrefs.toggle(ChatTweakPrefs.KEY_NO_PREVIEW, conversationId);
                            toast(context, on ? "Preview hidden" : "Preview shown");
                            dismiss(menuView, popover);
                            return null;
                        }
                        if (id == ITEM_AVATAR)
                        {
                            boolean on = ChatTweakPrefs.toggle(ChatTweakPrefs.KEY_NO_AVATAR, conversationId);
                            toast(context, on ? "Avatar hidden" : "Avatar shown");
                            dismiss(menuView, popover);
                            return null;
                        }
                        if (id == ITEM_RENAME)
                        {
                            dismiss(menuView, popover);
                            showRenameDialog(context, conversationId);
                            return null;
                        }
                    }
                }
                try { return method.invoke(original, args); }
                catch (Throwable t) { return null; }
            };
            Object proxy = Proxy.newProxyInstance(original.getClass().getClassLoader(), interfaces, handler);
            listenerField.set(holder, proxy);
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private static Object idOf(Object value)
    {
        if (value == null)
            return null;
        if (value instanceof Integer)
            return value;
        if (value instanceof Number number)
            return number.intValue();
        try
        {
            Method method = findNoArgMethod(value.getClass(), "getId");
            return method == null ? null : method.invoke(value);
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static void dismiss(Object menuView, Object popover)
    {
        try
        {
            Method method = findNoArgMethod(menuView.getClass(), "d7");
            if (method != null)
                method.invoke(menuView);
        }
        catch (Throwable ignored) { }
        try
        {
            if (popover != null)
            {
                Method method = findNoArgMethod(popover.getClass(), "dismiss");
                if (method == null)
                    method = findNoArgMethod(popover.getClass(), "hide");
                if (method != null)
                    method.invoke(popover);
            }
        }
        catch (Throwable ignored) { }
    }

    private static void toast(Context context, String message)
    {
        if (context == null)
            return;
        try { Toast.makeText(context, message, Toast.LENGTH_SHORT).show(); }
        catch (Throwable ignored) { }
    }

    private static void showRenameDialog(Context context, String conversationId)
    {
        if (context == null)
            return;
        new Handler(Looper.getMainLooper()).postDelayed(() ->
        {
            try
            {
                AlertDialog.Builder builder = new AlertDialog.Builder(
                    context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
                builder.setTitle("Rename locally");
                EditText input = new EditText(context);
                String current = ChatTweakPrefs.get(ChatTweakPrefs.KEY_CUSTOM_NAME, conversationId);
                if (current != null)
                    input.setText(current);
                input.setHint("Leave empty to reset");
                builder.setView(input);
                builder.setPositiveButton("Save", (dialog, which) ->
                {
                    ChatTweakPrefs.set(ChatTweakPrefs.KEY_CUSTOM_NAME, conversationId,
                        input.getText().toString());
                    toast(context, "Saved");
                });
                builder.setNegativeButton("Cancel", null);
                builder.show();
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }, 120);
    }

    // ---------------------------------------------------------- chat list bind

    private void hookChatListBind()
    {
        Method bind = findMethod("com.zing.zalo.ui.moduleview.message.NormalMsgModuleView",
            "T", "void", 3);
        if (bind == null)
            return;
        doHook(bind, chain ->
        {
            Object result = chain.proceed();
            if (!Config.getEnableCleanChatList())
                return result;
            try
            {
                Object self = chain.getThisObject();
                String conversationId = conversationIdOf(chain.getArg(0));
                applyCleanChatList(self, conversationId);
                if (self instanceof View view)
                    view.post(() -> applyCleanChatList(self, conversationId));
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
            return result;
        });
    }

    private static void applyCleanChatList(Object row, String conversationId)
    {
        if (row == null)
            return;

        Object timeModule = invokeNoArg(row, "getMTime");
        if (timeModule != null)
            invokeInt(timeModule, "o0", View.GONE);

        Object listItem = invokeNoArg(row, "getMListItemModule");
        List<Method> setters = charSequenceSetters(listItem);
        if (conversationId != null && ChatTweakPrefs.has(ChatTweakPrefs.KEY_NO_PREVIEW, conversationId)
            && !setters.isEmpty())
        {
            try { setters.get(0).invoke(listItem, ""); }
            catch (Throwable ignored) { }
        }
        if (conversationId != null)
        {
            String customName = ChatTweakPrefs.get(ChatTweakPrefs.KEY_CUSTOM_NAME, conversationId);
            if (customName != null && setters.size() >= 2)
            {
                try { setters.get(1).invoke(listItem, customName); }
                catch (Throwable ignored) { }
            }
        }

        Object avatar = invokeNoArg(row, "getMAvatar");
        if (avatar != null && conversationId != null
            && ChatTweakPrefs.has(ChatTweakPrefs.KEY_NO_AVATAR, conversationId))
            invokeInt(avatar, "o0", View.GONE);
    }

    // ----------------------------------------------------------- unread rows

    private void hookUnreadMethods()
    {
        String[] classes = {
            "com.zing.zalo.ui.moduleview.message.TabMsgCommonItemModuleView",
            "com.zing.zalo.ui.moduleview.message.NormalMsgModuleView"
        };
        for (String className : classes)
        {
            Method method = findMethod(className, "Z", "boolean", 2);
            if (method == null)
                continue;
            doHook(method, chain ->
            {
                if (!Config.getEnableChatHighlight())
                    return chain.proceed();
                String conversationId = conversationIdOf(chain.getArg(0));
                if (conversationId != null
                    && ChatTweakPrefs.has(ChatTweakPrefs.KEY_HIGHLIGHTED, conversationId))
                    return true;
                return chain.proceed();
            });
        }
    }

    // ------------------------------------------------------- read-only mode

    private void hookInputBar()
    {
        try
        {
            Class<?> inputBarClass = Class.forName(
                "com.zing.zalo.ui.chat.widget.inputbar.ChatInputBar", false, classLoader);
            for (Constructor<?> constructor : inputBarClass.getDeclaredConstructors())
            {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length != 5 || parameters[0] != Context.class || parameters[1] != String.class)
                    continue;
                doHook(constructor, chain ->
                {
                    Object result = chain.proceed();
                    if (!Config.getEnableReadonlyChats())
                        return result;
                    Object idArg = chain.getArg(1);
                    String conversationId = idArg instanceof String text ? text : null;
                    boolean locked = conversationId != null
                        && ChatTweakPrefs.has(ChatTweakPrefs.KEY_LOCKED, conversationId);
                    activeLockedConversation = locked ? conversationId : null;
                    if (locked)
                        lockInputBar(chain.getThisObject());
                    return result;
                });
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private static void lockInputBar(Object inputBar)
    {
        if (!(inputBar instanceof View view))
            return;
        view.setClickable(false);
        view.setOnTouchListener((v, event) -> true);
        if (view instanceof ViewGroup group)
        {
            for (int i = 0; i < group.getChildCount(); i++)
            {
                View child = group.getChildAt(i);
                child.setVisibility(View.GONE);
                child.setEnabled(false);
                child.setClickable(false);
                child.setOnTouchListener((v, event) -> true);
            }
        }
    }

    private void hookReactionPicker()
    {
        try
        {
            Class<?> pickerClass = Class.forName(
                "com.zing.zalo.ui.widget.reaction.ReactionPickerView", false, classLoader);
            for (Constructor<?> constructor : pickerClass.getDeclaredConstructors())
            {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length != 5 || parameters[0] != Context.class)
                    continue;
                doHook(constructor, chain ->
                {
                    Object result = chain.proceed();
                    if (Config.getEnableReadonlyChats() && activeLockedConversation != null
                        && result instanceof View view)
                        view.setVisibility(View.GONE);
                    return result;
                });
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private void hookChatRowControls()
    {
        Method reaction = findMethod("com.zing.zalo.ui.chat.chatrow.ChatRow", "I", "boolean", 2);
        if (reaction != null)
        {
            doHook(reaction, chain ->
                Config.getEnableReadonlyChats() && activeLockedConversation != null
                    ? Boolean.FALSE : chain.proceed());
        }
        Method share = findMethod("com.zing.zalo.ui.chat.chatrow.ChatRow", "R0", "boolean", 0);
        if (share != null)
        {
            doHook(share, chain ->
                Config.getEnableReadonlyChats() && activeLockedConversation != null
                    ? Boolean.FALSE : chain.proceed());
        }
    }
}
