package io.github.electroheavenvn.zaloxposed.hooks.functional;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.CoreUtilityHelper;
import io.github.electroheavenvn.zaloxposed.utilities.DexKitCache;
import io.github.electroheavenvn.zaloxposed.utilities.HookUtils;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.Utils;

/**
 * Notification and chat-list enhancements. Each feature is independently gated
 * by its own config key and fails open when a Zalo update moves an anchor.
 */
public class NotificationEnhancementsHook extends BaseHook
{
    private static final String ACTION_MARK_READ = "io.github.electroheavenvn.zaloxposed.action.MARK_READ";
    private static final String ACTION_MARK_ALL_READ = "io.github.electroheavenvn.zaloxposed.action.MARK_ALL_READ";
    private static final String RECEIVER = "com.zing.zalo.receiver.ZaloReceiver";
    private static final String KEY_TYPE = "EXTRA_KEY_TYPE";
    private static final String KEY_SUB_TYPE = "EXTRA_KEY_SUB_TYPE";

    private static final String EXTRA_CONV_ID = "io.github.electroheavenvn.zaloxposed.extra.CONV_ID";
    private static final String EXTRA_TYPE = "io.github.electroheavenvn.zaloxposed.extra.TYPE";
    private static final String EXTRA_SUB_TYPE = "io.github.electroheavenvn.zaloxposed.extra.SUB_TYPE";
    private static final String EXTRA_NOTIFICATION_ID = "io.github.electroheavenvn.zaloxposed.extra.NOTIFICATION_ID";
    private static final String EXTRA_ACCOUNT = "io.github.electroheavenvn.zaloxposed.extra.ACCOUNT";
    private static final String ACTION_MARKER = "io.github.electroheavenvn.zaloxposed.extra.MARK_READ_ACTION";

    private static final int REQUEST_CODE_SALT = 0x4E5A4D52;
    private static final int REQUEST_CODE_MARK_ALL = 0x4E5A4D41;

    private static final String ZSTYLE_NAME = "zStyle – Phong cách Zalo";

    /** Notification types whose EXTRA_KEY_SUB_TYPE is the conversation id. */
    private static final Set<String> CONVERSATION_TYPES = new HashSet<>(Arrays.asList(
        "chat_1_1", "chat_group", "hidden", "chat_reaction", "group_reaction", "message_error",
        "miss_call_zalo", "miss_call_stranger_zalo"));
    /** Group events are only trusted when the sub-type looks like a group conversation id. */
    private static final Set<String> GROUP_EVENT_TYPES = new HashSet<>(Arrays.asList(
        "change_background", "change_group_info", "create_poll", "add_member", "set_admin"));

    @Override
    public void hook() throws Throwable
    {
        if (Config.getEnableNotificationMarkRead())
        {
            hookNotificationPoster();
            hookReceiverActions();
        }
        if (Config.getEnablePromoNotificationFilter())
            hookPromoNotifications();
        if (Config.getHideCallHangupToast())
            hookHangupToast();
        if (Config.getHideZStyleConversation())
            hookConversationInsertion();
    }

    /* ------------------------------------------------------------------ */
    /* Mark as read from notifications                                     */
    /* ------------------------------------------------------------------ */

    /** Decorates ready-to-post notifications with the two read actions. */
    private void hookNotificationPoster()
    {
        try
        {
            List<Method> methods = DexKitCache.GetMethods("NotificationEnhancements_NotificationPoster", () -> bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                    .returnType("void")
                    .paramCount(3)
                    .paramTypes("int", "android.app.Notification", null)
                    .addUsingString("notify_skipped", StringMatchType.Equals)
                    .addUsingString("uid_changed", StringMatchType.Equals)
                    .addUsingString("com.zing.zalo.intent.action.DELETE_NOTIFICATION", StringMatchType.Equals)
                    .addUsingString("EXTRA_DATA_WRAP_NOTIFICATION_AUTO_CANCEL", StringMatchType.Equals)
                )), classLoader);
            for (Method method : methods)
            {
                try
                {
                    doHook(method, chain ->
                    {
                        if (!Config.getEnableNotificationMarkRead())
                            return chain.proceed();
                        Object id = chain.getArg(0);
                        Object notification = chain.getArg(1);
                        if (id instanceof Integer && notification instanceof Notification)
                            decorate((Integer)id, (Notification)notification);
                        return chain.proceed();
                    });
                }
                catch (Throwable t) { Logger.e(t); }
            }
        }
        catch (Throwable t) { Logger.e(t); }
    }

    /** Consumes our own broadcast actions before Zalo's receiver handles them. */
    private void hookReceiverActions()
    {
        try
        {
            List<Method> methods = DexKitCache.GetMethods("NotificationEnhancements_ReceiverHandle", () -> bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .declaredClass("com.zing.zalo.receiver.ZaloReceiver", StringMatchType.Equals)
                    .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                    .returnType("void")
                    .paramTypes("android.content.Context", "android.content.Intent")
                    .addUsingString("android.intent.action.ACTION_QUICK_MARK_AS_READ", StringMatchType.Equals)
                    .addUsingString("EXTRA_UID", StringMatchType.Equals)
                )), classLoader);
            for (Method method : methods)
            {
                try
                {
                    doHook(method, chain ->
                    {
                        if (!Config.getEnableNotificationMarkRead())
                            return chain.proceed();
                        Object context = chain.getArg(0);
                        Object intent = chain.getArg(1);
                        if (context instanceof Context && intent instanceof Intent
                            && handle((Context)context, (Intent)intent))
                            return null;
                        return chain.proceed();
                    });
                }
                catch (Throwable t) { Logger.e(t); }
            }
        }
        catch (Throwable t) { Logger.e(t); }
    }

    private void decorate(int id, Notification notification)
    {
        try
        {
            if (notification == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
                return;
            Bundle extras = notification.extras;
            String convId = conversationId(extras);
            if (convId == null)
                return;
            Context context = appContext();
            if (context == null)
                return;

            String type = extras.getString(KEY_TYPE);
            String subType = extras.getString(KEY_SUB_TYPE);
            String account = CoreUtilityHelper.GetCurrentUserID();

            Intent one = receiverIntent(context, ACTION_MARK_READ, account)
                .putExtra(EXTRA_CONV_ID, convId)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_SUB_TYPE, subType)
                .putExtra(EXTRA_NOTIFICATION_ID, id);
            Notification.Action markRead = action(context,
                label(!Utils.IsEnglish(), false),
                PendingIntent.getBroadcast(context, id ^ REQUEST_CODE_SALT, one, pendingIntentFlags()), true);
            if (markRead == null)
                return;

            Notification.Action markAll = null;
            if (activeConversationCount(context, id, convId) >= 2)
            {
                Intent all = receiverIntent(context, ACTION_MARK_ALL_READ, account);
                markAll = action(context,
                    label(!Utils.IsEnglish(), true),
                    PendingIntent.getBroadcast(context, REQUEST_CODE_MARK_ALL, all, pendingIntentFlags()), false);
            }

            // Keep Zalo's inline reply first so the system's visible-action limit
            // drops a shortcut rather than the read actions.
            List<Notification.Action> replies = new ArrayList<>();
            List<Notification.Action> others = new ArrayList<>();
            if (notification.actions != null)
            {
                for (Notification.Action existing : notification.actions)
                {
                    if (existing == null || isOurs(existing))
                        continue;
                    boolean reply = existing.getRemoteInputs() != null && existing.getRemoteInputs().length > 0;
                    (reply ? replies : others).add(existing);
                }
            }
            List<Notification.Action> result = new ArrayList<>(replies);
            result.add(markRead);
            if (markAll != null)
                result.add(markAll);
            result.addAll(others);
            notification.actions = result.toArray(new Notification.Action[0]);
        }
        catch (Throwable t) { Logger.e(t); }
    }

    /** Returns true only for our own actions, in which case the receiver is skipped. */
    private boolean handle(Context context, Intent intent)
    {
        final String action;
        try
        {
            action = intent == null ? null : intent.getAction();
            if (!ACTION_MARK_READ.equals(action) && !ACTION_MARK_ALL_READ.equals(action))
                return false;
        }
        catch (Throwable t)
        {
            Logger.e(t);
            return false;
        }
        try
        {
            Context app = context != null && context.getApplicationContext() != null
                ? context.getApplicationContext() : appContext();
            if (app == null)
                return true;
            Runnable work = () -> process(app, intent, ACTION_MARK_ALL_READ.equals(action));
            if (Looper.myLooper() == Looper.getMainLooper())
                new Thread(work, "notification-mark-read").start();
            else
                work.run();
        }
        catch (Throwable t) { Logger.e(t); }
        return true;
    }

    private void process(Context context, Intent intent, boolean all)
    {
        try
        {
            LinkedHashSet<String> convIds = new LinkedHashSet<>();
            Map<String, Object[]> dismiss = new LinkedHashMap<>();
            if (all)
            {
                for (StatusBarNotification sbn : activeNotifications(context))
                {
                    String convId = conversationId(sbn.getNotification() == null ? null : sbn.getNotification().extras);
                    if (convId == null)
                        continue;
                    convIds.add(convId);
                    dismiss.put(sbn.getTag() + "|" + sbn.getId(), new Object[] { sbn.getTag(), sbn.getId() });
                }
            }
            else
            {
                String convId = intent.getStringExtra(EXTRA_CONV_ID);
                if (!TextUtils.isEmpty(convId))
                    convIds.add(convId);
                int id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0);
                if (id != 0)
                    dismiss.put("null|" + id, new Object[] { null, id });
                String type = intent.getStringExtra(EXTRA_TYPE);
                String subType = intent.getStringExtra(EXTRA_SUB_TYPE);
                if (type != null)
                {
                    int hashed = Objects.hash(type, subType);
                    dismiss.put("null|" + hashed, new Object[] { null, hashed });
                }
            }

            String tappedAccount = intent.getStringExtra(EXTRA_ACCOUNT);
            String account = CoreUtilityHelper.GetCurrentUserID();
            boolean sameAccount = tappedAccount == null || account == null || tappedAccount.equals(account);
            boolean receiptAllowed = !Config.getHideReadReceipts();
            if (!sameAccount)
                Logger.i("Account changed since the notification was posted; dismiss only");
            else if (!convIds.isEmpty())
                markRead(new ArrayList<>(convIds), receiptAllowed);

            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null)
                return;
            for (Object[] entry : dismiss.values())
            {
                try { manager.cancel((String)entry[0], (Integer)entry[1]); }
                catch (Throwable t) { Logger.e(t); }
            }
        }
        catch (Throwable t) { Logger.e(t); }
    }

    private void markRead(ArrayList<String> ids, boolean receiptAllowed) throws Exception
    {
        // Zalo's MarkAsReadConversation clears the local unread state and
        // dismisses the conversation notifications. It also queues a seen ACK;
        // the shared read-receipt gate drops that ACK when receipts are not
        // allowed, so this action follows Config.getHideReadReceipts().
        Logger.d("Mark as read: " + ids.size() + " conversation(s), receipt allowed=" + receiptAllowed);
        Object useCase = Class.forName("cfj", false, classLoader).getMethod("A0").invoke(null);
        Object request = Class.forName("hcf", false, classLoader)
            .getConstructor(List.class).newInstance(ids);
        useCase.getClass().getMethod("L", Object.class).invoke(useCase, request);
    }

    private static String conversationId(Bundle extras)
    {
        if (extras == null)
            return null;
        String type = extras.getString(KEY_TYPE);
        String subType = extras.getString(KEY_SUB_TYPE);
        if (type == null || TextUtils.isEmpty(subType))
            return null;
        if (CONVERSATION_TYPES.contains(type))
            return subType;
        if (GROUP_EVENT_TYPES.contains(type) && subType.startsWith("group_"))
            return subType;
        return null;
    }

    private static int activeConversationCount(Context context, int postingId, String postingConvId)
    {
        Set<String> convIds = new HashSet<>();
        convIds.add(postingConvId);
        for (StatusBarNotification sbn : activeNotifications(context))
        {
            if (sbn.getId() == postingId && sbn.getTag() == null)
                continue;
            String convId = conversationId(sbn.getNotification() == null ? null : sbn.getNotification().extras);
            if (convId != null)
                convIds.add(convId);
        }
        return convIds.size();
    }

    private static StatusBarNotification[] activeNotifications(Context context)
    {
        try
        {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            StatusBarNotification[] active = manager == null ? null : manager.getActiveNotifications();
            if (active != null)
                return active;
        }
        catch (Throwable t) { Logger.e(t); }
        return new StatusBarNotification[0];
    }

    private static Context appContext()
    {
        Context context = CoreUtilityHelper.GetAppContext();
        return context != null ? context : HookUtils.currentApplication();
    }

    private static Intent receiverIntent(Context context, String action, String account)
    {
        Intent intent = new Intent(action).setComponent(new ComponentName(context.getPackageName(), RECEIVER));
        if (account != null)
            intent.putExtra(EXTRA_ACCOUNT, account);
        return intent;
    }

    private static int pendingIntentFlags()
    {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private static String label(boolean vietnamese, boolean all)
    {
        if (all)
            return vietnamese ? "Đánh dấu tất cả đã đọc" : "Mark all as read";
        return vietnamese ? "Đánh dấu đã đọc" : "Mark as read";
    }

    private static Notification.Action action(Context context, String label, PendingIntent intent, boolean markRead)
    {
        try
        {
            Icon icon = actionIcon(context);
            if (icon == null)
                icon = Icon.createWithResource(context, android.R.drawable.checkbox_on_background);
            Notification.Action.Builder builder = new Notification.Action.Builder(icon, label, intent);
            Bundle extras = new Bundle();
            extras.putBoolean(ACTION_MARKER, true);
            builder.addExtras(extras);
            if (markRead && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                builder.setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ);
            return builder.build();
        }
        catch (Throwable t)
        {
            Logger.e(t);
            return null;
        }
    }

    private static boolean isOurs(Notification.Action action)
    {
        Bundle extras = action.getExtras();
        return extras != null && extras.getBoolean(ACTION_MARKER, false);
    }

    /** Small filled check badge drawn locally so OEM launchers do not leave it untinted. */
    private static Icon actionIcon(Context context)
    {
        try
        {
            int size = Math.round(24f * context.getResources().getDisplayMetrics().density);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(0xFF979797);
            fill.setStyle(Paint.Style.FILL);
            canvas.drawCircle(size / 2f, size / 2f, size * 0.44f, fill);

            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(size * 0.09f);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            Path check = new Path();
            check.moveTo(size * 0.29f, size * 0.52f);
            check.lineTo(size * 0.45f, size * 0.68f);
            check.lineTo(size * 0.73f, size * 0.36f);
            canvas.drawPath(check, stroke);
            return Icon.createWithBitmap(bitmap);
        }
        catch (Throwable t)
        {
            Logger.e(t);
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Promo notification filter                                           */
    /* ------------------------------------------------------------------ */

    /**
     * The push dispatcher maps EXTRA_KEY_TYPE to a channel enum and hands the
     * channel to a posting method. Skipping that call for the Timeline/Stories
     * and Zalo Video enum constants drops the notification before it is built;
     * message, call, friend-request and birthday channels are never passed here.
     */
    private void hookPromoNotifications()
    {
        try
        {
            Set<Object> promoChannels = new HashSet<>();
            List<FieldData> channelFields = new ArrayList<>(bridge.findField(FindField.create()
                .matcher(FieldMatcher.create().name("SOCIAL_STORY"))));
            channelFields.addAll(bridge.findField(FindField.create()
                .matcher(FieldMatcher.create().name("ZALO_VIDEO"))));
            for (FieldData data : channelFields)
            {
                try
                {
                    Field field = data.getFieldInstance(classLoader);
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null)
                        promoChannels.add(value);
                }
                catch (Throwable t) { Logger.e(t); }
            }
            if (promoChannels.isEmpty())
            {
                Logger.e("Promo channel enum anchors not found");
                return;
            }

            List<MethodData> dispatchers = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("void")
                    .paramCount(0)
                    .addUsingString("EXTRA_KEY_TYPE", StringMatchType.Equals)
                    .addUsingField(FieldMatcher.create().name("SOCIAL_STORY"))));
            List<MethodData> video = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("void")
                    .paramCount(0)
                    .addUsingString("EXTRA_KEY_TYPE", StringMatchType.Equals)
                    .addUsingField(FieldMatcher.create().name("ZALO_VIDEO"))));
            for (MethodData data : video)
            {
                boolean known = false;
                for (MethodData existing : dispatchers)
                {
                    if (existing.getDescriptor().equals(data.getDescriptor()))
                    {
                        known = true;
                        break;
                    }
                }
                if (!known)
                    dispatchers.add(data);
            }
            if (dispatchers.isEmpty())
            {
                Logger.e("Promo notification dispatcher not found");
                return;
            }

            Set<String> hooked = new HashSet<>();
            for (MethodData dispatcher : dispatchers)
            {
                for (MethodData invoked : dispatcher.getInvokes())
                {
                    if (!"void".equals(invoked.getReturnTypeName())
                        || !hooked.add(invoked.getDescriptor()))
                        continue;
                    if (!takesPromoChannel(invoked, promoChannels))
                        continue;
                    try
                    {
                        Method method = invoked.getMethodInstance(classLoader);
                        doHook(method, chain ->
                        {
                            if (!Config.getEnablePromoNotificationFilter())
                                return chain.proceed();
                            for (Object arg : chain.getArgs())
                            {
                                if (promoChannels.contains(arg))
                                    return null;
                            }
                            return chain.proceed();
                        });
                    }
                    catch (Throwable t) { Logger.e(t); }
                }
            }
        }
        catch (Throwable t) { Logger.e(t); }
    }

    private boolean takesPromoChannel(MethodData method, Set<Object> promoChannels)
    {
        Set<Class<?>> channelClasses = new HashSet<>();
        for (Object channel : promoChannels)
            channelClasses.add(channel.getClass());
        for (String param : method.getParamTypeNames())
        {
            try
            {
                Class<?> type = Class.forName(param, false, classLoader);
                if (type == Object.class)
                    continue;
                for (Class<?> channelClass : channelClasses)
                {
                    if (type.isAssignableFrom(channelClass))
                        return true;
                }
            }
            catch (Throwable ignored) { }
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* Other-caller hangup toast                                           */
    /* ------------------------------------------------------------------ */

    /** Suppresses only the toast whose resource id is str_call_other_hangup_toast. */
    private void hookHangupToast()
    {
        try
        {
            List<FieldData> fields = bridge.findField(FindField.create()
                .matcher(FieldMatcher.create().name("str_call_other_hangup_toast").type(int.class)));
            if (fields.isEmpty())
            {
                Logger.e("Hangup toast resource anchor not found");
                return;
            }
            final int resId;
            try
            {
                Field field = fields.get(0).getFieldInstance(classLoader);
                field.setAccessible(true);
                resId = field.getInt(null);
            }
            catch (Throwable t)
            {
                Logger.e(t);
                return;
            }

            List<MethodData> owners = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .addUsingField(FieldMatcher.create().name("str_call_other_hangup_toast"))));
            Method toast = null;
            for (MethodData owner : owners)
            {
                for (MethodData invoked : owner.getInvokes())
                {
                    if (!Modifier.isStatic(invoked.getModifiers())
                        || !"void".equals(invoked.getReturnTypeName()))
                        continue;
                    List<String> params = invoked.getParamTypeNames();
                    if (params.size() != 3
                        || !"int".equals(params.get(0))
                        || !"android.content.Context".equals(params.get(1))
                        || !"java.lang.String".equals(params.get(2)))
                        continue;
                    toast = invoked.getMethodInstance(classLoader);
                    break;
                }
                if (toast != null)
                    break;
            }
            if (toast == null)
            {
                Logger.e("Hangup toast display method not found");
                return;
            }
            doHook(toast, chain ->
            {
                if (!Config.getHideCallHangupToast())
                    return chain.proceed();
                Object resourceId = chain.getArg(0);
                if (resourceId instanceof Integer && ((Integer)resourceId).intValue() == resId)
                    return null;
                return chain.proceed();
            });
        }
        catch (Throwable t) { Logger.e(t); }
    }

    /* ------------------------------------------------------------------ */
    /* Hidden conversation                                                 */
    /* ------------------------------------------------------------------ */

    /** Skips insertion of the ordinary conversation with the exact display name. */
    private void hookConversationInsertion()
    {
        try
        {
            List<Method> methods = DexKitCache.GetMethods("NotificationEnhancements_ConversationInsertion", () -> bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                    .returnType("void")
                    .addUsingString("ProfileManager: getContactProfileOrPutDefault uid invalid", StringMatchType.Equals)
                )), classLoader);
            for (Method method : methods)
            {
                if (method.getParameterCount() < 5 || method.getParameterCount() > 8)
                    continue;
                try
                {
                    doHook(method, chain ->
                    {
                        if (!Config.getHideZStyleConversation())
                            return chain.proceed();
                        return isHiddenConversation(chain.getArgs()) ? null : chain.proceed();
                    });
                }
                catch (Throwable t) { Logger.e(t); }
            }
        }
        catch (Throwable t) { Logger.e(t); }
    }

    private static boolean isHiddenConversation(List<Object> args)
    {
        for (Object arg : args)
        {
            if (arg == null || arg instanceof String)
                continue;
            Class<?> type = arg.getClass();
            while (type != null && type != Object.class)
            {
                try
                {
                    Field field = type.getDeclaredField("c");
                    if (field.getType() == String.class)
                    {
                        field.setAccessible(true);
                        if (ZSTYLE_NAME.equals(field.get(arg)))
                            return true;
                    }
                }
                catch (Throwable ignored) { }
                type = type.getSuperclass();
            }
        }
        return false;
    }
}
