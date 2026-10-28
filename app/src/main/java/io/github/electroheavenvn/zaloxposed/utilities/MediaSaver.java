package io.github.electroheavenvn.zaloxposed.utilities;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads Zalo media to the public Pictures/Zalo or Movies/Zalo folders.
 *
 * Zalo serves some images as JPEG XL; those are converted through Zalo's own
 * decoder when possible, with a .jxl fallback when conversion is unavailable.
 */
public final class MediaSaver
{
    private static final String FOLDER = "Zalo";
    private static final String JXL = "image/jxl";
    private static final int MAX_IN_FLIGHT = 3;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36";

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable ->
    {
        Thread thread = new Thread(runnable, "ZaloXposed-Download");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MediaSaver() { }

    public static String text(boolean english, String en, String vi)
    {
        return english ? en : vi;
    }

    public static void toast(Context context, String text)
    {
        if (context == null || text == null || text.isEmpty())
            return;
        Context app = context.getApplicationContext();
        if (app == null)
            app = context;
        final Context target = app;
        MAIN.post(() ->
        {
            try
            {
                Toast.makeText(target, text, Toast.LENGTH_SHORT).show();
            }
            catch (Throwable ignored) { }
        });
    }

    public static void save(Context context, String url, String name)
    {
        Context app = context.getApplicationContext();
        if (app == null)
            app = context;
        final Context target = app;
        if (IN_FLIGHT.incrementAndGet() > MAX_IN_FLIGHT)
        {
            IN_FLIGHT.decrementAndGet();
            toast(target, text(Utils.IsEnglish(), "Too many downloads are running.", "Đang có quá nhiều lượt tải xuống."));
            return;
        }
        toast(target, text(Utils.IsEnglish(), "Saving media...", "Đang lưu media..."));
        EXECUTOR.execute(() ->
        {
            File temp = null;
            try
            {
                temp = File.createTempFile("zaloxposed_", ".bin", target.getCacheDir());
                String[] result = fetch(target, url, temp);
                boolean video = result[0].startsWith("video/");
                publish(target, temp, name + "." + result[1], result[0], video);
                toast(target, text(Utils.IsEnglish(),
                    video ? "Video saved to Movies/Zalo." : "Photo saved to Pictures/Zalo.",
                    video ? "Đã lưu video vào Movies/Zalo." : "Đã lưu ảnh vào Pictures/Zalo."));
            }
            catch (Throwable t)
            {
                Logger.e("Download failed: " + t.getMessage());
                String reason = t.getMessage();
                if (reason == null || reason.isEmpty())
                    reason = t.getClass().getSimpleName();
                toast(target, text(Utils.IsEnglish(), "Download failed: ", "Tải xuống thất bại: ") + reason);
            }
            finally
            {
                if (temp != null && temp.exists())
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                IN_FLIGHT.decrementAndGet();
            }
        });
    }

    public static void saveAll(Context context, List<String> urls, String name, int missing, int streams)
    {
        Context app = context.getApplicationContext();
        if (app == null)
            app = context;
        final Context target = app;
        if (urls == null || urls.isEmpty())
            return;
        if (IN_FLIGHT.incrementAndGet() > MAX_IN_FLIGHT)
        {
            IN_FLIGHT.decrementAndGet();
            toast(target, text(Utils.IsEnglish(), "Too many downloads are running.", "Đang có quá nhiều lượt tải xuống."));
            return;
        }
        toast(target, text(Utils.IsEnglish(),
            "Saving " + urls.size() + " media item(s)...",
            "Đang lưu " + urls.size() + " media..."));
        EXECUTOR.execute(() ->
        {
            int saved = 0;
            try
            {
                for (int i = 0; i < urls.size(); i++)
                {
                    File temp = null;
                    try
                    {
                        temp = File.createTempFile("zaloxposed_", ".bin", target.getCacheDir());
                        String[] result = fetch(target, urls.get(i), temp);
                        boolean video = result[0].startsWith("video/");
                        publish(target, temp, name + "_" + (i + 1) + "." + result[1], result[0], video);
                        saved++;
                    }
                    catch (Throwable t)
                    {
                        Logger.e("Download failed " + (i + 1) + "/" + urls.size() + ": " + t.getMessage());
                    }
                    finally
                    {
                        if (temp != null && temp.exists())
                            //noinspection ResultOfMethodCallIgnored
                            temp.delete();
                    }
                }
            }
            finally
            {
                IN_FLIGHT.decrementAndGet();
                StringBuilder message = new StringBuilder(text(Utils.IsEnglish(),
                    "Saved " + saved + "/" + urls.size() + " media item(s).",
                    "Đã lưu " + saved + "/" + urls.size() + " media."));
                if (missing > 0)
                    message.append('\n').append(text(Utils.IsEnglish(),
                        missing + " more photo(s) are not in the feed payload.",
                        missing + " ảnh khác không có trong dữ liệu bài viết."));
                if (streams > 0)
                    message.append('\n').append(text(Utils.IsEnglish(),
                        streams + " HLS stream(s) were skipped.",
                        streams + " luồng HLS đã được bỏ qua."));
                toast(target, message.toString());
            }
        });
    }

    private static String[] fetch(Context context, String url, File temp) throws Exception
    {
        String mime = mimeOf(download(url, temp), url);
        if (JXL.equals(mime) || isJxl(temp))
        {
            File jpeg = new File(temp.getPath() + ".jpg");
            try
            {
                if (convertJxl(context.getClassLoader(), temp, jpeg))
                {
                    copyFile(jpeg, temp);
                    mime = "image/jpeg";
                }
                else
                    mime = JXL;
            }
            finally
            {
                if (jpeg.exists())
                    //noinspection ResultOfMethodCallIgnored
                    jpeg.delete();
            }
        }
        String extension;
        if (JXL.equals(mime))
            extension = "jxl";
        else
        {
            extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (extension == null || extension.isEmpty())
                extension = mime.startsWith("video/") ? "mp4" : "jpg";
        }
        return new String[]{mime, extension};
    }

    private static String mimeOf(String header, String url)
    {
        if (header != null)
        {
            int separator = header.indexOf(';');
            String clean = (separator >= 0 ? header.substring(0, separator) : header).trim().toLowerCase(Locale.ROOT);
            if (clean.startsWith("video/") || clean.startsWith("image/"))
                return clean;
        }
        String path = Uri.parse(url).getPath();
        if (path == null)
            path = url;
        String extension = MimeTypeMap.getFileExtensionFromUrl(path).toLowerCase(Locale.ROOT);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime != null ? mime : "image/jpeg";
    }

    private static String download(String url, File destination) throws IOException
    {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try
        {
            int code = connection.getResponseCode();
            if (code < 200 || code > 299)
                throw new IOException("HTTP " + code);
            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(destination))
            {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1)
                    output.write(buffer, 0, read);
                output.flush();
            }
            return connection.getContentType();
        }
        finally
        {
            connection.disconnect();
        }
    }

    private static void publish(Context context, File file, String name, String mime, boolean video) throws Exception
    {
        String root = video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, root + "/" + FOLDER);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver resolver = context.getContentResolver();
            Uri collection = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            Uri uri = resolver.insert(collection, values);
            if (uri == null)
                throw new IllegalStateException("MediaStore insert failed");
            try
            {
                try (OutputStream output = resolver.openOutputStream(uri))
                {
                    if (output == null)
                        throw new IOException("Cannot open MediaStore output");
                    copyFile(file, output);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            }
            catch (Throwable t)
            {
                try
                {
                    resolver.delete(uri, null, null);
                }
                catch (Throwable ignored) { }
                throw t;
            }
        }
        else
        {
            File directory = new File(Environment.getExternalStoragePublicDirectory(root), FOLDER);
            if (!directory.exists() && !directory.mkdirs())
                throw new IllegalStateException("Cannot create " + directory.getAbsolutePath());
            File destination = new File(directory, name);
            try
            {
                copyFile(file, destination);
            }
            catch (IOException e)
            {
                throw new IllegalStateException(e);
            }
            MediaScannerConnection.scanFile(context, new String[]{destination.getPath()}, new String[]{mime}, null);
        }
    }

    private static void copyFile(File source, File destination) throws IOException
    {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination))
        {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1)
                output.write(buffer, 0, read);
            output.flush();
        }
    }

    private static void copyFile(File source, OutputStream output) throws IOException
    {
        try (InputStream input = new FileInputStream(source))
        {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1)
                output.write(buffer, 0, read);
            output.flush();
        }
    }

    private static boolean isJxl(File file)
    {
        byte[] head = new byte[8];
        int read;
        try (InputStream input = new FileInputStream(file))
        {
            read = input.read(head);
        }
        catch (Throwable t)
        {
            return false;
        }
        if (read >= 2 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0x0a)
            return true;
        if (read < 8)
            return false;
        byte[] container = new byte[]{0, 0, 0, 0x0c, 0x4a, 0x58, 0x4c, 0x20};
        for (int i = 0; i < container.length; i++)
        {
            if (head[i] != container[i])
                return false;
        }
        return true;
    }

    private static boolean convertJxl(ClassLoader loader, File source, File destination)
    {
        try
        {
            Class<?> decoder = Class.forName("com.zing.zalo.zmedia.zjxl.ZJXLDecoder", false, loader);
            Class<?> options = Class.forName("com.zing.zalo.zmedia.zjxl.ZJXLDecoder$Options", false, loader);
            Method info = null;
            Method decode = null;
            for (Method method : decoder.getDeclaredMethods())
            {
                if (!Modifier.isStatic(method.getModifiers())
                    || Modifier.isNative(method.getModifiers())
                    || method.getReturnType() != double.class)
                    continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 2
                    && parameters[0] == String.class
                    && parameters[1] == options)
                    info = method;
                else if (parameters.length == 3
                    && parameters[0] == String.class
                    && parameters[1] == int.class
                    && parameters[2] == Bitmap.class)
                    decode = method;
            }
            if (info == null || decode == null)
                return false;
            Method error = decoder.getDeclaredMethod("jxlGetErrorCode", double.class);
            error.setAccessible(true);
            info.setAccessible(true);
            decode.setAccessible(true);

            Object optionValues = options.getDeclaredConstructor().newInstance();
            Object infoResult = info.invoke(null, source.getPath(), optionValues);
            if (!isJxlOk(error, infoResult))
                return false;

            Field widthField = options.getDeclaredField("bmPreviewWidth");
            Field heightField = options.getDeclaredField("bmPreviewHeight");
            widthField.setAccessible(true);
            heightField.setAccessible(true);
            int width = widthField.getInt(optionValues);
            int height = heightField.getInt(optionValues);
            if (width <= 0 || height <= 0)
                return false;

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try
            {
                Object decodeResult = decode.invoke(null, source.getPath(), 1, bitmap);
                if (!isJxlOk(error, decodeResult))
                    return false;
                try (OutputStream output = new FileOutputStream(destination))
                {
                    return bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output);
                }
            }
            finally
            {
                bitmap.recycle();
            }
        }
        catch (Throwable t)
        {
            Logger.e("JXL conversion failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean isJxlOk(Method errorMethod, Object result) throws Exception
    {
        Object code = errorMethod.invoke(null, result);
        return code instanceof Number && ((Number)code).intValue() == 0;
    }
}
