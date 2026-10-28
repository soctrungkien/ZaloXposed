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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Adds Download actions to Zalo's story, photo and video menus and handles the
 * selected media. The implementation is based on the readonly NexAlloy patch
 * and uses the same model-independent reflection strategy.
 */
public class DownloadMediaHook extends BaseHook
{
    private static final String MENU_VIEW = "com.zing.zalo.social.features.menu_feed.BottomSheetMenuView";
    private static final String BUNDLE_DATA = "com.zing.zalo.social.features.menu_feed.model.BottomSheetMenuBundleData";

    private static final int MENU_ITEM_ID = 20056;
    private static final int ALL_ITEM_ID = 20057;
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
        resolveStory();
        resolveViewfull();
        hookStoryMenu();
        hookMenuClick();
        hookViewfullMenu();
    }

    private void resolveStory()
    {
        List<MethodData> storyJson = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .returnType("org.json.JSONObject")
                .paramCount(0)
                .addUsingString("urlHls", StringMatchType.Equals)
                .addUsingString("serverFileId", StringMatchType.Equals)
                .addUsingString("delayTime", StringMatchType.Equals)
            ));
        MethodData storyJsonData = firstNonStatic(storyJson);
        if (storyJsonData == null)
            Logger.e("Story JSON method not found");
        else
        {
            try
            {
                storyJsonMethod = storyJsonData.getMethodInstance(classLoader);
                storyJsonMethod.setAccessible(true);
                storyClass = storyJsonMethod.getDeclaringClass();
            }
            catch (Throwable t)
            {
                Logger.e("Cannot resolve story JSON method: " + t.getMessage());
            }
        }

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

            Set<String> descriptors = new LinkedHashSet<>();
            MethodData factoryData = null;
            for (MethodData invoked : builderData.getInvokes())
            {
                if (!Modifier.isStatic(invoked.getModifiers())
                    || invoked.getParamCount() != 10
                    || "void".equals(invoked.getReturnTypeName())
                    || !invoked.getParamTypeNames().contains("android.graphics.drawable.Drawable"))
                    continue;
                if (!descriptors.add(invoked.getDescriptor()))
                    continue;
                factoryData = invoked;
            }
            if (factoryData == null)
            {
                Logger.e("Story menu item factory not found");
                return;
            }
            factoryMethod = factoryData.getMethodInstance(classLoader);
            factoryMethod.setAccessible(true);

            Class<?> companionType = factoryMethod.getParameterTypes()[0];
            Object companion = null;
            for (Field field : factoryMethod.getReturnType().getDeclaredFields())
            {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != companionType)
                    continue;
                field.setAccessible(true);
                companion = field.get(null);
                break;
            }
            if (companion == null)
            {
                Logger.e("Story menu item companion not found");
                return;
            }
            factoryCompanion = companion;

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
            for (Field field : bundleType.getDeclaredFields())
            {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != int.class)
                    continue;
                field.setAccessible(true);
                sheetTypeField = field;
                break;
            }

            for (Method method : viewfullMethod.getDeclaringClass().getDeclaredMethods())
            {
                if (method.getParameterCount() != 0)
                    continue;
                boolean hasBundle = false;
                for (Field field : method.getReturnType().getDeclaredFields())
                {
                    if (field.getType() == bundleType)
                    {
                        hasBundle = true;
                        break;
                    }
                }
                if (!hasBundle)
                    continue;
                method.setAccessible(true);
                presenterGetter = method;
                break;
            }
            if (presenterGetter == null)
            {
                Logger.e("Photo/video menu presenter getter not found");
                return;
            }

            Class<?> presenterType = presenterGetter.getReturnType();
            for (Field field : presenterType.getDeclaredFields())
            {
                if (field.getType() == bundleType && presenterBundleField == null)
                {
                    field.setAccessible(true);
                    presenterBundleField = field;
                }
                else if (field.getType() == List.class && presenterItemsField == null)
                {
                    field.setAccessible(true);
                    presenterItemsField = field;
                }
            }
            if (presenterBundleField == null || presenterItemsField == null || sheetTypeField == null)
            {
                Logger.e("Photo/video menu presenter fields not found");
                return;
            }

            List<MethodData> feedJson = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .returnType("org.json.JSONObject")
                    .paramCount(0)
                    .addUsingString("fid", StringMatchType.Equals)
                    .addUsingString("ftype", StringMatchType.Equals)
                    .addUsingString("feed_title", StringMatchType.Equals)
                    .addUsingString("content", StringMatchType.Equals)
                ));
            MethodData feedData = firstNonStatic(feedJson);
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
                List<Object> list = (List<Object>)result;
                Object data = chain.getArg(0);
                Context context = (Context)ZaloModel.valueOfType(data, Context.class);
                Object story = ZaloModel.reachable(data, storyClass);
                if (context == null || story == null)
                    return result;
                Resources resources = context.getResources();
                int icon = resources.getIdentifier(DOWNLOAD_ICON, "drawable", context.getPackageName());
                int title = resources.getIdentifier(DOWNLOAD_TITLE, "string", context.getPackageName());
                if (icon == 0 || title == 0)
                    return result;
                for (Object item : list)
                {
                    if (item != null && ZaloModel.holdsInt(item, icon))
                        return result;
                }
                Object item = factoryMethod.invoke(null, factoryCompanion, MENU_ITEM_ID, icon,
                    resources.getString(title), null, false, null, null, null, DEFAULTS_MASK);
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
                {
                    if (media.getUrls().size() == 1 && media.getMissing() == 0)
                        MediaSaver.save(context, media.getUrls().get(0), "zalo_" + media.getId());
                    else
                        MediaSaver.saveAll(context, media.getUrls(), "zalo_" + media.getId(),
                            media.getMissing(), media.getStreams());
                }
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
                Bundle bundle = (Bundle)bundleObject;
                List<Object> sourceItems = (List<Object>)itemsObject;
                List<Object> items = sourceItems;
                Context context = appContext();
                if (context == null)
                    return result;
                Resources resources = context.getResources();
                int icon = resources.getIdentifier(MEDIA_ICON, "drawable", context.getPackageName());
                if (icon == 0)
                    return result;

                int sheet = sheetTypeField.getInt(bundle);
                int nativeId = 0;
                String nativeTitle = null;
                if (sheet == PHOTO_SHEET)
                {
                    nativeId = PHOTO_DOWNLOAD_ID;
                    nativeTitle = PHOTO_TITLE;
                }
                else if (sheet == VIDEO_SHEET)
                {
                    nativeId = VIDEO_DOWNLOAD_ID;
                    nativeTitle = VIDEO_TITLE;
                }

                boolean changed = false;
                if (nativeId != 0)
                {
                    boolean exists = false;
                    for (Object item : items)
                    {
                        if (item != null && ZaloModel.holdsInt(item, icon))
                        {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists)
                    {
                        int titleId = resources.getIdentifier(nativeTitle, "string", context.getPackageName());
                        if (titleId != 0)
                        {
                            Object item = factoryMethod.invoke(null, factoryCompanion, nativeId, icon,
                                resources.getString(titleId), null, false, null, null, null, DEFAULTS_MASK);
                            if (item != null)
                            {
                                items.add(0, item);
                                changed = true;
                            }
                        }
                    }
                }
                else
                {
                    Object feed = ZaloModel.find(bundle, feedClass, 3);
                    FeedMedia media = feed != null ? FeedMedia.of(feedJsonMethod, feed) : null;
                    if (media != null && !media.getUrls().isEmpty())
                    {
                        boolean single = media.getUrls().size() == 1 && media.getMissing() == 0;
                        String title;
                        if (!single)
                        {
                            int count = media.getUrls().size() + media.getMissing();
                            title = (Utils.IsEnglish() ? "Download all (" : "Tải tất cả (") + count + ")";
                        }
                        else
                        {
                            int titleId = resources.getIdentifier(
                                media.getVideos() > 0 ? VIDEO_TITLE : PHOTO_TITLE,
                                "string", context.getPackageName());
                            title = titleId != 0 ? resources.getString(titleId) : "";
                        }
                        if (!title.isEmpty())
                        {
                            Object item = factoryMethod.invoke(null, factoryCompanion, ALL_ITEM_ID, icon,
                                title, null, false, null, null, null, DEFAULTS_MASK);
                            if (item != null)
                            {
                                feeds.put(item, media);
                                items.add(0, item);
                                changed = true;
                            }
                        }
                    }
                }
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

    private void saveStory(Context context, Object story)
    {
        try
        {
            Object value = storyJsonMethod.invoke(story);
            if (!(value instanceof JSONObject))
            {
                MediaSaver.toast(context, Utils.IsEnglish()
                    ? "No downloadable media found."
                    : "Không tìm thấy media có thể tải.");
                return;
            }
            JSONObject json = (JSONObject)value;
            String url = json.optString("url");
            if (url == null || !url.startsWith("http") || url.contains(".m3u8"))
            {
                boolean stream = !json.optString("urlHls").isEmpty();
                MediaSaver.toast(context, stream
                    ? (Utils.IsEnglish() ? "This story only provides an HLS stream." : "Story này chỉ có luồng HLS.")
                    : (Utils.IsEnglish() ? "No downloadable media found." : "Không tìm thấy media có thể tải."));
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

    private boolean closeSheet(Object sheet)
    {
        if (sheet == null)
            return false;
        Class<?> current = sheet.getClass();
        while (current != null && current != Object.class)
        {
            for (Method method : current.getDeclaredMethods())
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
            current = current.getSuperclass();
        }
        return false;
    }

    private Context appContext()
    {
        Context context = CustomFont.getAppContext();
        if (context != null)
            return context;
        try
        {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = activityThread.getMethod("currentApplication").invoke(null);
            if (application instanceof Context)
                return (Context)application;
        }
        catch (Throwable ignored) { }
        return null;
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
