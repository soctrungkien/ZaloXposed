package io.github.electroheavenvn.zaloxposed.hooks.functional;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.CustomFont;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.MediaSaver;
import io.github.electroheavenvn.zaloxposed.utilities.Utils;
import io.github.electroheavenvn.zaloxposed.utilities.ZaloModel;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Adds Save actions to the post "..." sheet (photos and videos) and to the
 * Zalo Video action sheet. All anchors are resolved at runtime and every
 * missing anchor is ignored so the host keeps its original behaviour.
 */
public class ExtendedMediaSaveHook extends BaseHook
{
    private static final String MENU_VIEW = "com.zing.zalo.social.features.menu_feed.BottomSheetMenuView";
    private static final String BUNDLE_DATA = "com.zing.zalo.social.features.menu_feed.model.BottomSheetMenuBundleData";
    private static final String VIDEO_SHEET = "com.zing.zalo.shortvideo.ui.component.bts.VideoActionBottomSheet";
    private static final String VIDEO_ITEM = "ok7";
    private static final String CONTENT = "com.zing.zalo.shortvideo.domain.entity.content.Content";
    private static final String VIDEO = "com.zing.zalo.shortvideo.domain.entity.content.Video";
    private static final String SHORT_HANDLER_FIELD = "Q1";

    private static final int VIDEO_ROW_ID = 9001;
    private static final int PHOTO_ROW_ID = 9002;
    private static final int SHORT_VIDEO_ROW_ID = 9001;
    private static final int DEFAULTS_MASK = 0x1f0;
    private static final String DOWNLOAD_ICON = "ic_bottom_sheet_menu_download";
    private static final int SHORT_VIDEO_ICON = 0x7f080c21;

    private final Map<Object, SaveEntry> rows = Collections.synchronizedMap(new WeakHashMap<>());
    private final ThreadLocal<Object> shortSheet = new ThreadLocal<>();
    private volatile Context cachedContext;

    private Method sheetBuild;
    private Method rowFactory;
    private Object rowCompanion;
    private Class<?> bundleType;
    private Class<?> shortRowType;
    private Method presenterGetter;
    private Field presenterItemsField;
    private Method shortListBuilder;
    private Method shortClick;

    private static final class SaveEntry
    {
        final int id;
        final List<String[]> videos;
        final List<String> photos;

        SaveEntry(int id, List<String[]> videos, List<String> photos)
        {
            this.id = id;
            this.videos = videos;
            this.photos = photos;
        }
    }

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getEnableExtendedMediaSave())
            return;
        try
        {
            resolveFeedSheet();
            hookFeedSheet();
            hookFeedClick();
            resolveShortVideo();
            hookShortVideoClick();
        }
        catch (Throwable t)
        {
            Logger.e("Extended media save setup failed: " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------- resolve

    private void resolveFeedSheet()
    {
        try
        {
            MethodData data = first(bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .declaredClass(MENU_VIEW)
                    .returnType("void")
                    .paramTypes("android.os.Bundle")
                    .addUsingString("EXTRA_BUNDLE_DATA", StringMatchType.Equals)
                    .addUsingString("hiddenAreaHeight", StringMatchType.Equals)
                )));
            if (data == null)
            {
                Logger.e("Extended media save: feed menu sheet not found");
                return;
            }
            sheetBuild = data.getMethodInstance(classLoader);
            bundleType = Class.forName(BUNDLE_DATA, false, classLoader);

            for (MethodData invoked : data.getInvokes())
            {
                if (!Modifier.isStatic(invoked.getModifiers())
                    || invoked.getParamCount() != 10
                    || "void".equals(invoked.getReturnTypeName())
                    || !invoked.getParamTypeNames().contains("android.graphics.drawable.Drawable"))
                    continue;
                rowFactory = invoked.getMethodInstance(classLoader);
                rowFactory.setAccessible(true);
                rowCompanion = findCompanion(rowFactory);
                break;
            }
            if (rowFactory == null || rowCompanion == null)
            {
                Logger.e("Extended media save: menu row factory not found");
                return;
            }
            presenterGetter = findPresenterGetter(sheetBuild.getDeclaringClass(), bundleType);
            if (presenterGetter == null)
                return;
            presenterItemsField = firstField(presenterGetter.getReturnType(),
                f -> !Modifier.isStatic(f.getModifiers()) && f.getType() == List.class);
            if (presenterItemsField == null)
            {
                Logger.e("Extended media save: menu item list not found");
                return;
            }

            MethodData clickData = first(bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .declaredClass(MENU_VIEW)
                    .returnType("void")
                    .paramCount(1)
                    .paramTypes(rowFactory.getReturnType().getName())
                )));
            if (clickData != null)
                feedClick = clickData.getMethodInstance(classLoader);
        }
        catch (Throwable t)
        {
            Logger.e("Extended media save: cannot resolve feed menu: " + t.getMessage());
        }
    }

    private Method feedClick;

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

    private static Method findPresenterGetter(Class<?> owner, Class<?> bundleType)
    {
        for (Method method : owner.getDeclaredMethods())
        {
            if (method.getParameterCount() != 0 || method.getReturnType().isPrimitive())
                continue;
            if (firstField(method.getReturnType(), f -> f.getType() == bundleType) == null)
                continue;
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private void resolveShortVideo()
    {
        try
        {
            MethodData buildData = first(bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .declaredClass(VIDEO_SHEET)
                    .returnType("android.view.View")
                    .paramCount(3)
                    .paramTypes("android.view.LayoutInflater", "android.view.ViewGroup", "android.os.Bundle")
                )));
            if (buildData == null)
            {
                Logger.e("Extended media save: video action sheet build not found");
                return;
            }
            Method build = buildData.getMethodInstance(classLoader);
            for (MethodData invoked : buildData.getInvokes())
            {
                if (!"java.util.ArrayList".equals(invoked.getReturnTypeName()))
                    continue;
                shortListBuilder = invoked.getMethodInstance(classLoader);
                shortListBuilder.setAccessible(true);
                break;
            }
            if (shortListBuilder == null)
            {
                Logger.e("Extended media save: video action row list builder not found");
                return;
            }
            shortRowType = Class.forName(VIDEO_ITEM, false, classLoader);
            MethodData clickData = first(bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .declaredClass(VIDEO_SHEET)
                    .returnType("void")
                    .paramCount(1)
                    .paramTypes(VIDEO_ITEM)
                )));
            if (clickData == null)
            {
                Logger.e("Extended media save: video action click not found");
                return;
            }
            shortClick = clickData.getMethodInstance(classLoader);
            hookShortVideoBuild(build);
        }
        catch (Throwable t)
        {
            Logger.e("Extended media save: cannot resolve video action sheet: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ hooks

    private void hookFeedSheet()
    {
        if (sheetBuild == null || rowFactory == null || rowCompanion == null
            || presenterGetter == null || presenterItemsField == null)
            return;
        module.hook(sheetBuild).intercept(chain ->
        {
            Object result = chain.proceed();
            if (!Config.getEnableExtendedMediaSave())
                return result;
            try
            {
                if (chain.getArg(0) instanceof Bundle)
                    addPostRows((Bundle)chain.getArg(0), chain.getThisObject());
            }
            catch (Throwable t)
            {
                Logger.e("Extended media save: cannot add post save rows: " + t.getMessage());
            }
            return result;
        });
    }

    private void hookFeedClick()
    {
        if (feedClick == null)
            return;
        module.hook(feedClick).intercept(chain ->
        {
            Object item = chain.getArg(0);
            SaveEntry entry = rows.get(item);
            if (entry == null)
                return chain.proceed();
            Context context = appContext();
            if (context != null)
                saveEntry(context, entry);
            closeSheet(chain.getThisObject());
            return null;
        });
    }

    private void hookShortVideoBuild(Method build)
    {
        module.hook(build).intercept(chain ->
        {
            Object sheet = chain.getThisObject();
            shortSheet.set(sheet);
            try
            {
                return chain.proceed();
            }
            finally
            {
                shortSheet.remove();
            }
        });
        module.hook(shortListBuilder).intercept(chain ->
        {
            Object result = chain.proceed();
            if (!Config.getEnableExtendedMediaSave())
                return result;
            try
            {
                Object sheet = shortSheet.get();
                if (result instanceof List && sheet != null && isDownloadable(shortVideo(sheet)))
                    addShortRow((List<Object>)result, sheet);
            }
            catch (Throwable t)
            {
                Logger.e("Extended media save: cannot add video save row: " + t.getMessage());
            }
            return result;
        });
    }

    private void hookShortVideoClick()
    {
        if (shortClick == null || shortRowType == null)
            return;
        module.hook(shortClick).intercept(chain ->
        {
            Object item = chain.getArg(0);
            if (!isShortRow(item))
                return chain.proceed();
            Object sheet = chain.getThisObject();
            Object video = shortVideo(sheet);
            String mp4 = videoLink(video, "h");
            String hls = videoLink(video, "g");
            dismiss(sheet);
            Context context = appContext();
            if (context != null && (mp4 != null || hls != null))
            {
                String name = "zalo_video_" + stamp();
                try
                {
                    if (mp4 != null)
                        MediaSaver.save(context, mp4, name);
                    else
                        MediaSaver.saveHls(context, hls, name);
                }
                catch (Throwable t)
                {
                    Logger.e("Extended media save: video save failed: " + t.getMessage());
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------- post menu

    @SuppressWarnings("unchecked")
    private void addPostRows(Bundle bundle, Object sheet) throws Throwable
    {
        Object data = findBundleData(bundle);
        if (data == null)
            return;
        int type = intField(data, "a", -1);
        Object selection = objectField(data, "b", true);
        Object post = null;
        if (type == 1)
        {
            Object group = objectField(selection, "b", true);
            String feedId = stringField(selection, "c");
            if (group != null && feedId != null)
                post = postForFeed(group, feedId);
        }
        else if (type == 3 || type == 4 || type == 5)
        {
            post = objectField(selection, "d", true);
        }
        if (post == null)
            return;
        Object media = objectField(post, "n", true);
        if (media == null)
            return;
        Object presenter = presenterGetter.invoke(sheet);
        if (presenter == null)
            return;
        Object value = presenterItemsField.get(presenter);
        if (!(value instanceof List))
            return;
        List<Object> items = (List<Object>)value;
        Context context = appContext();
        if (context == null)
            return;
        int icon = resId(context, DOWNLOAD_ICON, "drawable");
        if (icon == 0)
            return;

        List<String> photos = findPhotos(post, media);
        int morePhotos = intField(media, "m", 0);
        if (!photos.isEmpty() && morePhotos == 0)
        {
            String title = text(photos.size() == 1
                ? new String[]{"Download photo", "Tải ảnh về máy"}
                : new String[]{"Download all photos (" + photos.size() + ")", "Tải tất cả ảnh (" + photos.size() + ")"});
            addRow(items, PHOTO_ROW_ID, icon, title, null, new ArrayList<>(photos));
        }
        List<String[]> videos = findVideos(post);
        if (!videos.isEmpty())
        {
            String title = text(videos.size() == 1
                ? new String[]{"Download video", "Tải video về máy"}
                : new String[]{"Download all videos (" + videos.size() + ")", "Tải tất cả video (" + videos.size() + ")"});
            addRow(items, VIDEO_ROW_ID, icon, title, new ArrayList<>(videos), null);
        }
    }

    private void addRow(List<Object> items, int id, int icon, String title,
                        List<String[]> videos, List<String> photos) throws Throwable
    {
        if (hasId(items, id) || title == null || title.isEmpty())
            return;
        Object row = rowFactory.invoke(null, rowCompanion, id, icon, title,
            null, false, null, null, null, DEFAULTS_MASK);
        if (row == null)
            return;
        rows.put(row, new SaveEntry(id, videos, photos));
        items.add(row);
    }

    private Object postForFeed(Object group, String feedId) throws Throwable
    {
        for (Class<?> c = group.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        {
            for (Method method : c.getDeclaredMethods())
            {
                Class<?>[] types = method.getParameterTypes();
                if (!"d".equals(method.getName()) || types.length != 1
                    || types[0] != String.class || method.getReturnType().isPrimitive())
                    continue;
                method.setAccessible(true);
                return method.invoke(group, feedId);
            }
        }
        return null;
    }

    /** Content type 2/3 uses one photo list; type 23 uses a mixed album. */
    private static List<String> findPhotos(Object post, Object media)
    {
        List<String> urls = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try
        {
            int type = intField(post, "b", -1);
            if (type == 2 || type == 3)
            {
                Object list = objectField(media, "h", false);
                if (list instanceof List)
                {
                    for (Object item : (List<?>)list)
                    {
                        if (item == null)
                            continue;
                        String url = url(objectField(item, "i", false));
                        if (url != null && seen.add(url))
                            urls.add(url);
                    }
                }
            }
            else if (type == 23)
            {
                Object album = objectField(media, "M", false);
                if (album instanceof List)
                {
                    for (Object entry : (List<?>)album)
                    {
                        if (entry == null || intField(entry, "a", -1) != 0)
                            continue;
                        Object item = objectField(entry, "b", false);
                        if (item == null)
                            continue;
                        String url = url(objectField(item, "c", false));
                        if (url != null && seen.add(url))
                            urls.add(url);
                    }
                }
            }
        }
        catch (Throwable t)
        {
            Logger.e("Extended media save: photo lookup failed: " + t.getMessage());
            urls.clear();
        }
        return urls;
    }

    /** Each entry is {progressive mp4, HLS}; album videos come first, then the single video. */
    private static List<String[]> findVideos(Object post)
    {
        List<String[]> videos = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try
        {
            Object media = objectField(post, "n", true);
            if (media == null)
                return videos;
            Object album = objectField(media, "M", false);
            if (album instanceof List)
            {
                for (Object entry : (List<?>)album)
                {
                    if (entry == null || intField(entry, "a", -1) != 1)
                        continue;
                    Object item = objectField(entry, "b", false);
                    if (item == null)
                        continue;
                    String mp4 = url(objectField(item, "c", false));
                    String hls = url(objectField(item, "f", false));
                    addVideo(videos, seen, mp4, hls);
                }
            }
            if (videos.isEmpty())
            {
                Object video = objectField(media, "y", true);
                if (video != null)
                    addVideo(videos, seen, url(objectField(video, "c", false)), url(objectField(video, "d", false)));
            }
        }
        catch (Throwable t)
        {
            Logger.e("Extended media save: video lookup failed: " + t.getMessage());
            videos.clear();
        }
        return videos;
    }

    private static void addVideo(List<String[]> videos, Set<String> seen, String mp4, String hls)
    {
        if (mp4 == null && hls == null)
            return;
        String key = mp4 != null ? mp4 : hls;
        if (!seen.add(key))
            return;
        videos.add(new String[]{mp4, hls});
    }

    private void saveEntry(Context context, SaveEntry entry)
    {
        String stamp = stamp();
        if (entry.photos != null && !entry.photos.isEmpty())
        {
            MediaSaver.saveAll(context, new ArrayList<>(entry.photos),
                "zalo_post_" + stamp, 0, 0);
        }
        if (entry.videos != null)
        {
            for (int i = 0; i < entry.videos.size(); i++)
            {
                String[] video = entry.videos.get(i);
                String mp4 = video.length > 0 ? video[0] : null;
                String hls = video.length > 1 ? video[1] : null;
                String name = "zalo_video_" + stamp
                    + (entry.videos.size() > 1 ? "_" + (i + 1) : "");
                try
                {
                    if (mp4 != null)
                        MediaSaver.save(context, mp4, name);
                    else if (hls != null)
                        MediaSaver.saveHls(context, hls, name);
                }
                catch (Throwable t)
                {
                    Logger.e("Extended media save: cannot save video: " + t.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------ short video

    private static Object shortVideo(Object sheet)
    {
        try
        {
            if (sheet == null)
                return null;
            Class<?> contentClass = Class.forName(CONTENT, false, sheet.getClass().getClassLoader());
            Class<?> videoClass = Class.forName(VIDEO, false, sheet.getClass().getClassLoader());
            Object handler = objectField(sheet, SHORT_HANDLER_FIELD, true);
            if (handler == null)
                return null;
            for (Class<?> c = handler.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            {
                for (Field field : c.getDeclaredFields())
                {
                    if (Modifier.isStatic(field.getModifiers()))
                        continue;
                    if (!contentClass.isAssignableFrom(field.getType()) && field.getType() != Object.class)
                        continue;
                    field.setAccessible(true);
                    Object value = field.get(handler);
                    if (value != null && videoClass.isInstance(value))
                        return value;
                }
            }
        }
        catch (Throwable t)
        {
            Logger.e("Extended media save: video lookup failed: " + t.getMessage());
        }
        return null;
    }

    private static boolean isDownloadable(Object video)
    {
        return videoLink(video, "h") != null || videoLink(video, "g") != null;
    }

    private static String videoLink(Object video, String field)
    {
        return url(objectField(video, field, false));
    }

    private void addShortRow(List<Object> items, Object sheet)
    {
        try
        {
            for (Constructor<?> constructor : shortRowType.getDeclaredConstructors())
            {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length != 6 || types[0] != int.class || types[2] != Integer.class
                    || types[3] != String.class || types[4] != String.class || types[5] != String.class)
                    continue;
                Context context = appContext();
                if (context == null)
                    return;
                int icon = resId(context, DOWNLOAD_ICON, "drawable");
                if (icon == 0)
                    icon = SHORT_VIDEO_ICON;
                constructor.setAccessible(true);
                items.add(constructor.newInstance(SHORT_VIDEO_ROW_ID, null, Integer.valueOf(icon),
                    text(new String[]{"Download video", "Tải video về máy"}), null, null));
                return;
            }
            Logger.e("Extended media save: video row constructor not found");
        }
        catch (Throwable t)
        {
            Logger.e("Extended media save: cannot add video row: " + t.getMessage());
        }
    }

    private boolean isShortRow(Object item)
    {
        if (item == null || shortRowType == null || !shortRowType.getName().equals(item.getClass().getName()))
            return false;
        return intField(item, "a", -1) == SHORT_VIDEO_ROW_ID;
    }

    private static void dismiss(Object sheet)
    {
        try
        {
            if (sheet != null)
                sheet.getClass().getMethod("w6").invoke(sheet);
        }
        catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- helpers

    private Object findBundleData(Bundle bundle)
    {
        if (bundleType == null || bundle == null)
            return null;
        try
        {
            for (String key : bundle.keySet())
            {
                Object value = bundle.get(key);
                if (bundleType.isInstance(value))
                    return value;
            }
        }
        catch (Throwable ignored) { }
        return ZaloModel.find(bundle, bundleType, 3);
    }

    private boolean hasId(List<Object> items, int id)
    {
        for (Object item : items)
        {
            if (ZaloModel.holdsInt(item, id))
                return true;
        }
        return false;
    }

    private interface FieldFilter { boolean accept(Field field); }

    private static Field firstField(Class<?> type, FieldFilter filter)
    {
        if (type == null)
            return null;
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

    private static Object objectField(Object target, String name, boolean fallback)
    {
        if (target == null)
            return null;
        try
        {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            {
                try
                {
                    Field field = c.getDeclaredField(name);
                    if (Modifier.isStatic(field.getModifiers()))
                        continue;
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value != null)
                        return value;
                }
                catch (NoSuchFieldException ignored) { }
            }
        }
        catch (Throwable ignored) { }
        if (!fallback)
            return null;
        for (Field field : ZaloModel.fields(target.getClass()))
        {
            Class<?> type = field.getType();
            if (type.isPrimitive() || type.getName().startsWith("java.")
                || type.getName().startsWith("android.") || type.getName().startsWith("kotlin."))
                continue;
            try
            {
                field.setAccessible(true);
                Object value = field.get(target);
                if (value != null)
                    return value;
            }
            catch (Throwable ignored) { }
        }
        return null;
    }

    private static int intField(Object target, String name, int fallback)
    {
        if (target == null)
            return fallback;
        try
        {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            {
                try
                {
                    Field field = c.getDeclaredField(name);
                    if (field.getType() == int.class)
                    {
                        field.setAccessible(true);
                        return field.getInt(target);
                    }
                }
                catch (NoSuchFieldException ignored) { }
            }
        }
        catch (Throwable ignored) { }
        if (name == null || name.isEmpty())
            return fallback;
        Field field = firstField(target.getClass(),
            f -> !Modifier.isStatic(f.getModifiers()) && f.getType() == int.class);
        if (field == null)
            return fallback;
        try
        {
            return field.getInt(target);
        }
        catch (Throwable ignored)
        {
            return fallback;
        }
    }

    private static String stringField(Object target, String name)
    {
        if (target == null)
            return null;
        try
        {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            {
                try
                {
                    Field field = c.getDeclaredField(name);
                    if (field.getType() == String.class)
                    {
                        field.setAccessible(true);
                        return (String)field.get(target);
                    }
                }
                catch (NoSuchFieldException ignored) { }
            }
        }
        catch (Throwable ignored) { }
        Field field = firstField(target.getClass(),
            f -> !Modifier.isStatic(f.getModifiers()) && f.getType() == String.class);
        if (field == null)
            return null;
        try
        {
            return (String)field.get(target);
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static String url(Object value)
    {
        if (!(value instanceof String))
            return null;
        String url = ((String)value).trim();
        if (url.isEmpty() || "null".equals(url) || !url.startsWith("http"))
            return null;
        return url;
    }

    private static void closeSheet(Object sheet)
    {
        try
        {
            if (sheet == null)
                return;
            for (Class<?> c = sheet.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            {
                for (Method method : c.getDeclaredMethods())
                {
                    if (!"close".equals(method.getName()) || method.getParameterCount() != 0)
                        continue;
                    method.setAccessible(true);
                    method.invoke(sheet);
                    return;
                }
            }
        }
        catch (Throwable ignored) { }
    }

    private static String stamp()
    {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private static String text(String[] enAndVi)
    {
        return MediaSaver.text(Utils.IsEnglish(), enAndVi[0], enAndVi[1]);
    }

    private Context appContext()
    {
        Context context = cachedContext;
        if (context != null)
            return context;
        context = CustomFont.getAppContext();
        cachedContext = context;
        return context;
    }

    private static int resId(Context context, String name, String type)
    {
        Resources resources = context.getResources();
        return resources.getIdentifier(name, type, context.getPackageName());
    }

    private static <T> T first(List<T> list)
    {
        return list == null || list.isEmpty() ? null : list.get(0);
    }
}
