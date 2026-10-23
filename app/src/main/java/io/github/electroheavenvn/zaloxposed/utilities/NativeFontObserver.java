package io.github.electroheavenvn.zaloxposed.utilities;

import android.app.Activity;
import android.app.Application;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Spanned;
import android.text.method.PasswordTransformationMethod;
import android.text.style.MetricAffectingSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Applies the selected font to text views after each activity layout.
 *
 * Some Zalo components set their typeface directly in Java, after a layout
 * pass, so a global layout listener is used instead of a one-shot traversal.
 * Rich text spans, emoji/private-use glyphs, password fields and WebViews are
 * intentionally skipped.
 */
public final class NativeFontObserver implements Application.ActivityLifecycleCallbacks
{
    private static NativeFontObserver instance;
    private final Map<Activity, Tracker> trackers = new HashMap<>();

    private NativeFontObserver() { }

    public static synchronized void register(Application application)
    {
        if (instance != null || application == null)
            return;
        instance = new NativeFontObserver();
        application.registerActivityLifecycleCallbacks(instance);
        Logger.d("Native font observer registered");
    }

    @Override
    public void onActivityResumed(Activity activity)
    {
        try
        {
            Tracker tracker = trackers.get(activity);
            if (tracker == null)
            {
                tracker = new Tracker(activity);
                trackers.put(activity, tracker);
            }
            tracker.schedule();
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity)
    {
        Tracker tracker = trackers.remove(activity);
        if (tracker != null)
            tracker.close();
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

    private static final class Tracker implements ViewTreeObserver.OnGlobalLayoutListener
    {
        private final View root;
        private final ViewTreeObserver observer;
        private final WeakHashMap<TextView, Typeface> applied = new WeakHashMap<>();
        private boolean scheduled;
        private boolean closed;
        private final Runnable scan;

        Tracker(Activity activity)
        {
            root = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
            scan = () ->
            {
                scheduled = false;
                if (closed || root == null)
                    return;
                try
                {
                    Typeface font = CustomFont.load(root.getContext());
                    if (font != null)
                        visit(root, font);
                }
                catch (Throwable t)
                {
                    Logger.e(t);
                }
            };
            observer = root != null ? root.getViewTreeObserver() : null;
            if (observer != null)
                observer.addOnGlobalLayoutListener(this);
        }

        @Override
        public void onGlobalLayout()
        {
            schedule();
        }

        void schedule()
        {
            if (scheduled || closed || root == null)
                return;
            scheduled = true;
            root.postDelayed(scan, 120);
        }

        void close()
        {
            closed = true;
            if (root != null)
                root.removeCallbacks(scan);
            if (observer != null && observer.isAlive())
                observer.removeOnGlobalLayoutListener(this);
            applied.clear();
        }

        private void visit(View view, Typeface font)
        {
            if (view == null || view instanceof WebView)
                return;
            if (view instanceof TextView)
                apply((TextView)view, font);
            if (view instanceof ViewGroup)
            {
                ViewGroup group = (ViewGroup)view;
                for (int index = 0; index < group.getChildCount(); index++)
                    visit(group.getChildAt(index), font);
            }
        }

        private void apply(TextView text, Typeface baseFont)
        {
            try
            {
                if (text.getTransformationMethod() instanceof PasswordTransformationMethod)
                    return;
                CharSequence value = text.getText();
                if (value == null)
                    return;
                if (value instanceof Spanned && ((Spanned)value).getSpans(0, value.length(), MetricAffectingSpan.class).length != 0)
                    return;
                for (int offset = 0; offset < value.length(); )
                {
                    int codePoint = Character.codePointAt(value, offset);
                    if (Character.getType(codePoint) == Character.PRIVATE_USE)
                        return;
                    offset += Character.charCount(codePoint);
                }
                Typeface current = text.getTypeface();
                if (applied.containsKey(text) && current == applied.get(text))
                    return;
                Typeface font = baseFont;
                if (current != null)
                {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        font = Typeface.create(font, current.getWeight(), current.isItalic());
                    else
                        font = Typeface.create(font, current.getStyle());
                }
                applied.put(text, font);
                text.setTypeface(font);
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }
    }
}
