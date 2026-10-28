package io.github.electroheavenvn.zaloxposed.utilities;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Extracts downloadable media URLs from a Zalo feed/post JSON object.
 */
public final class FeedMedia
{
    private static final String ORIGIN = "origin";
    private static final String THUMB = "thumb";
    private static final String HLS = "hls";
    private static final String MORE = "more_photo";
    private static final String VIDEO = "video";

    private final String id;
    private final List<String> urls;
    private final int missing;
    private final int streams;
    private final int videos;

    private FeedMedia(String id, List<String> urls, int missing, int streams, int videos)
    {
        this.id = id;
        this.urls = urls;
        this.missing = missing;
        this.streams = streams;
        this.videos = videos;
    }

    public String getId()
    {
        return id;
    }

    public List<String> getUrls()
    {
        return urls;
    }

    public int getMissing()
    {
        return missing;
    }

    public int getStreams()
    {
        return streams;
    }

    public int getVideos()
    {
        return videos;
    }

    public static FeedMedia of(Method feedJsonMethod, Object feed)
    {
        try
        {
            Object value = feedJsonMethod.invoke(feed);
            if (!(value instanceof JSONObject))
                return null;
            JSONObject json = (JSONObject)value;
            JSONObject content = json.optJSONObject("content");
            if (content == null)
                return null;

            LinkedHashSet<String> urls = new LinkedHashSet<>();
            int[] counters = new int[]{0, 0}; // streams, videos
            walk(content, null, urls, counters);

            String fid = sanitize(json.optString("fid"));
            if (fid.isEmpty())
                fid = String.valueOf(System.currentTimeMillis());
            int missing = Math.max(0, content.optInt(MORE, 0));
            return new FeedMedia("feed_" + fid, new ArrayList<>(urls), missing, counters[0], counters[1]);
        }
        catch (Throwable t)
        {
            Logger.e("Cannot read feed media: " + t.getMessage());
            return null;
        }
    }

    private static void walk(Object node, String key, LinkedHashSet<String> urls, int[] counters)
    {
        if (node instanceof JSONObject)
        {
            JSONObject object = (JSONObject)node;
            String origin = link(object.optString(ORIGIN));
            boolean hls = !object.optString(HLS).isEmpty();
            if (origin != null)
            {
                if (urls.add(origin) && VIDEO.equals(key))
                    counters[1]++;
            }
            else if (hls)
                counters[0]++;
            else if (object.has(ORIGIN))
            {
                String thumb = link(object.optString(THUMB));
                if (thumb != null)
                    urls.add(thumb);
            }

            Iterator<String> keys = object.keys();
            while (keys.hasNext())
            {
                String childKey = keys.next();
                walk(object.opt(childKey), childKey, urls, counters);
            }
        }
        else if (node instanceof JSONArray)
        {
            JSONArray array = (JSONArray)node;
            for (int i = 0; i < array.length(); i++)
                walk(array.opt(i), key, urls, counters);
        }
    }

    private static String link(String value)
    {
        if (value == null || !value.startsWith("http") || value.contains(".m3u8"))
            return null;
        return value;
    }

    public static String sanitize(String value)
    {
        if (value == null)
            return "";
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c))
                builder.append(c);
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }
}
