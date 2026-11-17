package io.github.electroheavenvn.zaloxposed.hooks.functional;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.CustomFont;
import io.github.electroheavenvn.zaloxposed.utilities.FeedMedia;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.MediaSaver;
import io.github.electroheavenvn.zaloxposed.utilities.Utils;
import io.github.electroheavenvn.zaloxposed.utilities.ZaloModel;

import org.json.JSONObject;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class DownloadMediaHook extends BaseHook
{
    private static final String MENU_VIEW = "com.zing.zalo.social.features.menu_feed.BottomSheetMenuView";
    private static final String BUNDLE_DATA = "com.zing.zalo.social.features.menu_feed.model.BottomSheetMenuBundleData";

    private static final int MENU_ITEM_ID = 36701;
    private static final int ALL_ITEM_ID = 36702;
    private static final int DEFAULTS_MASK = 0x1f0;
    private static final int PHOTO_DOWNLOAD_ID = 0x17;
    private static final int VIDEO_DOWNLOAD_ID = 0x1c;
    private static final int PHOTO_SHEET = 6;
    private static final int VIDEO_SHEET = 7;

    private static final String DOWNLOAD_ICON = "ic_bottom_sheet_story_download";
    private static final String DOWNLOAD_TITLE = "str_bottom_sheet_story_download_tittle";
    private static final String MEDIA_ICON = "ic_bottom_sheet_menu_download";
    private static final String PHOTO_TITLE = "str_bottom_sheet_menu_photo_download";
    private static final String VIDEO_TITLE = "str_bottom_sheet_menu_video_download";

    private final Map<Object, Object> stories = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, FeedMedia> feeds = Collections.synchronizedMap(new WeakHashMap<>());

    private volatile Context cachedContext;

    private Method storyJsonMethod;
    private Method storyMenuBuilder;
    private Method factoryMethod;
    private Object factoryCompanion;
    private Method clickMethod;

    private Method viewfullMethod;
    private Method presenterGetter;
    private Field presenterBundleField;
    private Field presenterItemsField;
    private Field sheetTypeField;
    private Method feedJsonMethod;
    private Class<?> feedClass;
    private Class<?> storyClass;

    @Override
    public void hook() throws Throwable
    {
        resolveStoryJson();
        resolveStoryMenu();
        resolveViewfull();
        hookStoryMenu();
        hookMenuClick();
        hookViewfullMenu();
    }

    // ---------------------------------------------------------------- resolve

    private void resolveStoryJson()
    {
        MethodData data = firstNonStatic(bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .returnType("org.json.JSONObject")
                .paramCount(0)
                .addUsingString("urlHls", StringMatchType.Equals)
                .addUsingString("serverFileId", StringMatchType.Equals)
                .addUsingString("delayTime", StringMatchType.Equals)
            )));
        if (data == null)
        {
            Logger.e("Story JSON method not found");
            return;
        }
        try
        {
            storyJsonMethod = data.getMethodInstance(classLoader);
            storyJsonMethod.setAccessible(true);
            storyClass = storyJsonMethod.getDeclaringClass();
        }
        catch (Throwable t)
        {
            Logger.e("Cannot resolve story JSON method: " + t.getMessage());
        }
    }

    private void resolveStoryMenu()
    {
        List<MethodData> builders = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .returnType("java.util.List")
                .addUsingString("dataStoryViewFull", StringMatchType.Equals)
            ));
        if (builders.isEmpty())
        {
            Logger.e("Story menu builder not found");
            return;
        }
        try
        {
            MethodData builderData = builders.get(0);
            storyMenuBuilder = builderData.getMethodInstance(classLoader);

            MethodData factoryData = findItemFactory(builderData);
            if (factoryData == null)
            {
                Logger.e("Story menu item factory not found");
                return;
            }
            factoryMethod = factoryData.getMethodInstance(classLoader);
            factoryMethod.setAccessible(true);

            factoryCompanion = findCompanion(factoryMethod);
            if (factoryCompanion == null)
            {
                Logger.e("Story menu item companion not found");
                return;
            }

            List<MethodData> clicks = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .declaredClass(MENU_VIEW)
                    .paramCount(1)
                    .paramTypes(factoryMethod.getReturnType().getName())
                    .returnType("void")
                ));
            if (clicks.isEmpty())
            {
                Logger.e("Menu item click method not found");
                return;
            }
            clickMethod = clicks.get(0).getMethodInstance(classLoader);
            Logger.i("Hooking download menu: " + storyMenuBuilder + ", " + clickMethod);
        }
        catch (Throwable t)
        {
            Logger.e("Cannot resolve story download menu: " + t.getMessage());
        }
    }

    private static MethodData findItemFactory(MethodData builderData)
    {
        Set<String> seen = new LinkedHashSet<>();
        MethodData found = null;
        for (MethodData invoked : builderData.getInvokes())
        {
            if (!Modifier.isStatic(invoked.getModifiers())
                || invoked.getParamCount() != 10
                || "void".equals(invoked.getReturnTypeName())
                || !invoked.getParamTypeNames().contains("android.graphics.drawable.Drawable"))
                continue;
            if (seen.add(invoked.getDescriptor()))
                found = invoked; // giữ hành vi cũ: lấy ứng viên cuối cùng
        }
        return found;
    }

    private static Object findCompanion(Method factory) throws IllegalAccessException
    {
        Class<?> companionType = factory.getParameterTypes()[0];
        for (Field field : factory.getReturnType().getDeclaredFields())
        {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != companionType)
                continue;
            field.setAccessible(true);
            return field.get(null);
        }
        return null;
    }

    private void resolveViewfull()
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .declaredClass(MENU_VIEW)
                .paramTypes("android.os.Bundle")
                .returnType("void")
                .addUsingString("EXTRA_BUNDLE_DATA", StringMatchType.Equals)
                .addUsingString("hiddenAreaHeight", StringMatchType.Equals)
                .addUsingNumber(PHOTO_DOWNLOAD_ID)
                .addUsingNumber(VIDEO_DOWNLOAD_ID)
            ));
        if (methods.isEmpty())
        {
            Logger.e("Photo/video menu method not found");
            return;
        }
        try
        {
            viewfullMethod = methods.get(0).getMethodInstance(classLoader);
            Class<?> bundleType = Class.forName(BUNDLE_DATA, false, classLoader);

            sheetTypeField = firstField(bundleType, f -> !Modifier.isStatic(f.getModifiers()) && f.getType() == int.class);
            presenterGetter = findPresenterGetter(viewfullMethod.getDeclaringClass(), bundleType);
            if (presenterGetter == null)
            {
                Logger.e("Photo/video menu presenter getter not found");
                return;
            }

            Class<?> presenterType = presenterGetter.getReturnType();
            presenterBundleField = firstField(presenterType, f -> f.getType() == bundleType);
            presenterItemsField = firstField(presenterType, f -> f.getType() == List.class);
            if (presenterBundleField == null || presenterItemsField == null || sheetTypeField == null)
            {
                Logger.e("Photo/video menu presenter fields not found");
                return;
            }

            MethodData feedData = firstNonStatic(bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("org.json.JSONObject")
                    .paramCount(0)
                    .addUsingString("fid", StringMatchType.Equals)
                    .addUsingString("ftype", StringMatchType.Equals)
                    .addUsingString("feed_title", StringMatchType.Equals)
                    .addUsingString("content", StringMatchType.Equals)
                )));
            if (feedData == null)
            {
                Logger.e("Feed JSON method not found");
                return;
            }
            feedJsonMethod = feedData.getMethodInstance(classLoader);
            feedJsonMethod.setAccessible(true);
            feedClass = feedJsonMethod.getDeclaringClass();
            Logger.i("Hooking photo/video download menu: " + viewfullMethod);
        }
        catch (Throwable t)
        {
            Logger.e("Cannot resolve photo/video download menu: " + t.getMessage());
        }
    }

    private static Method findPresenterGetter(Class<?> owner, Class<?> bundleType)
    {
        for (Method method : owner.getDeclaredMethods())
        {
            if (method.getParameterCount() != 0)
                continue;
            if (firstField(method.getReturnType(), f -> f.getType() == bundleType) == null)
                continue;
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private interface FieldFilter { boolean accept(Field field); }

    private static Field firstField(Class<?> type, FieldFilter filter)
    {
        for (Field field : type.getDeclaredFields())
        {
            if (filter.accept(field))
            {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ hooks

    private void hookStoryMenu()
    {
        if (storyMenuBuilder == null || factoryMethod == null || factoryCompanion == null
            || storyClass == null)
            return;
        module.hook(storyMenuBuilder).intercept(chain ->
        {
            Object result = chain.proceed();
            if (!Config.getEnableDownload() || !(result instanceof List))
                return result;
            try
            {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>)result;
                Object data = chain.getArg(0);
                Context context = (Context)ZaloModel.valueOfType(data, Context.class);
                Object story = ZaloModel.reachable(data, storyClass);
                if (context == null || story == null)
                    return result;

                int icon = resId(context, DOWNLOAD_ICON, "drawable");
                int title = resId(context, DOWNLOAD_TITLE, "string");
                if (icon == 0 || title == 0 || hasInt(list, icon))
                    return result;

                Object item = newItem(MENU_ITEM_ID, icon, context.getResources().getString(title));
                if (item != null)
                {
                    stories.put(item, story);
                    list.add(item);
                }
            }
            catch (Throwable t)
            {
                Logger.e("Cannot add story download item: " + t.getMessage());
            }
            return result;
        });
    }

    private void hookMenuClick()
    {
        if (clickMethod == null)
            return;
        module.hook(clickMethod).intercept(chain ->
        {
            if (!Config.getEnableDownload())
                return chain.proceed();

            Object item = chain.getArg(0);

            Object story = stories.get(item);
            if (story != null)
            {
                Context context = appContext();
                if (context != null)
                    saveStory(context, story);
                return chain.proceed();
            }

            FeedMedia media = feeds.get(item);
            if (media != null)
            {
                Context context = appContext();
                if (context != null)
                    saveFeed(context, media);
                if (closeSheet(chain.getThisObject()))
                    return null;
            }
            return chain.proceed();
        });
    }

    private void hookViewfullMenu()
    {
        if (viewfullMethod == null || factoryMethod == null || factoryCompanion == null
            || presenterGetter == null || presenterBundleField == null
            || presenterItemsField == null || sheetTypeField == null)
            return;
        module.hook(viewfullMethod).intercept(chain ->
        {
            Object result = chain.proceed();
            if (!Config.getEnableDownload())
                return result;
            try
            {
                Object presenter = presenterGetter.invoke(chain.getThisObject());
                if (presenter == null)
                    return result;
                Object bundleObject = presenterBundleField.get(presenter);
                Object itemsObject = presenterItemsField.get(presenter);
                if (!(bundleObject instanceof Bundle) || !(itemsObject instanceof List))
                    return result;

                Context context = appContext();
                if (context == null)
                    return result;
                int icon = resId(context, MEDIA_ICON, "drawable");
                if (icon == 0)
                    return result;

                Bundle bundle = (Bundle)bundleObject;
                @SuppressWarnings("unchecked")
                List<Object> items = (List<Object>)itemsObject;

                boolean changed;
                int sheet = sheetTypeField.getInt(bundle);
                if (sheet == PHOTO_SHEET)
                    changed = addNativeItem(context, items, icon, PHOTO_DOWNLOAD_ID, PHOTO_TITLE);
                else if (sheet == VIDEO_SHEET)
                    changed = addNativeItem(context, items, icon, VIDEO_DOWNLOAD_ID, VIDEO_TITLE);
                else
                    changed = addFeedItem(context, items, icon, bundle);

                if (changed)
                    presenterItemsField.set(presenter, items);
            }
            catch (Throwable t)
            {
                Logger.e("Cannot add photo/video download item: " + t.getMessage());
            }
            return result;
        });
    }

    // ------------------------------------------------------------ menu items

    private boolean addNativeItem(Context context, List<Object> items, int icon, int id, String titleName)
        throws Throwable
    {
        if (hasInt(items, icon))
            return false;
        int titleId = resId(context, titleName, "string");
        if (titleId == 0)
            return false;
        Object item = newItem(id, icon, context.getResources().getString(titleId));
        if (item == null)
            return false;
        items.add(0, item);
        return true;
    }

    private boolean addFeedItem(Context context, List<Object> items, int icon, Bundle bundle)
        throws Throwable
    {
        if (hasInt(items, ALL_ITEM_ID))
            return false;
        Object feed = ZaloModel.find(bundle, feedClass, 3);
        FeedMedia media = feed != null ? FeedMedia.of(feedJsonMethod, feed) : null;
        if (media == null || media.getUrls().isEmpty())
            return false;

        String title;
        if (isSingle(media))
        {
            int titleId = resId(context, media.getVideos() > 0 ? VIDEO_TITLE : PHOTO_TITLE, "string");
            title = titleId != 0 ? context.getResources().getString(titleId) : "";
        }
        else
        {
            int count = media.getUrls().size() + media.getMissing();
            title = text("Download all (", "Tải tất cả (") + count + ")";
        }
        if (title.isEmpty())
            return false;

        Object item = newItem(ALL_ITEM_ID, icon, title);
        if (item == null)
            return false;
        feeds.put(item, media);
        items.add(0, item);
        return true;
    }

    private Object newItem(int id, int icon, String title) throws Throwable
    {
        return factoryMethod.invoke(null, factoryCompanion, id, icon, title,
            null, false, null, null, null, DEFAULTS_MASK);
    }

    private static boolean hasInt(List<Object> items, int value)
    {
        for (Object item : items)
        {
            if (item != null && ZaloModel.holdsInt(item, value))
                return true;
        }
        return false;
    }

    // ----------------------------------------------------------------- saving

    private static boolean isSingle(FeedMedia media)
    {
        return media.getUrls().size() == 1 && media.getMissing() == 0;
    }

    private void saveFeed(Context context, FeedMedia media)
    {
        String name = "zalo_" + media.getId();
        if (isSingle(media))
            MediaSaver.save(context, media.getUrls().get(0), name);
        else
            MediaSaver.saveAll(context, media.getUrls(), name, media.getMissing(), media.getStreams());
    }

    private void saveStory(Context context, Object story)
    {
        try
        {
            Object value = storyJsonMethod.invoke(story);
            if (!(value instanceof JSONObject))
            {
                MediaSaver.toast(context, noMedia());
                return;
            }
            JSONObject json = (JSONObject)value;
            String url = json.optString("url");
            if (!url.startsWith("http") || url.contains(".m3u8"))
            {
                boolean hlsOnly = !json.optString("urlHls").isEmpty();
                MediaSaver.toast(context, hlsOnly
                    ? text("This story only provides an HLS stream.", "Story này chỉ có luồng HLS.")
                    : noMedia());
                return;
            }
            String owner = FeedMedia.sanitize(json.optString("ownerId"));
            if (owner.isEmpty())
                owner = "user";
            String id = FeedMedia.sanitize(json.optString("id"));
            if (id.isEmpty())
                id = String.valueOf(System.currentTimeMillis());
            MediaSaver.save(context, url, "zalo_story_" + owner + "_" + id);
        }
        catch (Throwable t)
        {
            Logger.e("Cannot save story: " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String text(String en, String vi)
    {
        return Utils.IsEnglish() ? en : vi;
    }

    private static String noMedia()
    {
        return text("No downloadable media found.", "Không tìm thấy media có thể tải.");
    }

    private static int resId(Context context, String name, String type)
    {
        Resources resources = context.getResources();
        return resources.getIdentifier(name, type, context.getPackageName());
    }

    private boolean closeSheet(Object sheet)
    {
        if (sheet == null)
            return false;
        for (Class<?> c = sheet.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        {
            for (Method method : c.getDeclaredMethods())
            {
                if (!"close".equals(method.getName()) || method.getParameterCount() != 0)
                    continue;
                try
                {
                    method.setAccessible(true);
                    method.invoke(sheet);
                    return true;
                }
                catch (Throwable ignored)
                {
                    return false;
                }
            }
        }
        return false;
    }

    private Context appContext()
    {
        Context context = cachedContext;
        if (context != null)
            return context;
        context = CustomFont.getAppContext();
        if (context == null)
        {
            try
            {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Object application = activityThread.getMethod("currentApplication").invoke(null);
                if (application instanceof Context)
                    context = (Context)application;
            }
            catch (Throwable ignored) { }
        }
        cachedContext = context;
        return context;
    }

    private static MethodData firstNonStatic(List<MethodData> methods)
    {
        for (MethodData method : methods)
        {
            if (!Modifier.isStatic(method.getModifiers()))
                return method;
        }
        return null;
    }
}