package io.github.electroheavenvn.zaloxposed.hooks.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Port of readonly's liquid tab bar. It restyles Zalo's main tab container and
 * animates the selected tab with a translucent rounded background.
 */
public class LiquidTabHook extends BaseHook
{
    private static final String FRAME_LAYOUT = "com.zing.zalo.uicontrol.MainTabFrameLayout";
    private static final String CUSTOM_MAIN_TAB = "com.zing.zalo.ui.maintab.widget.CustomMainTab";
    private static final String TAB_MAIN_VIEW = "com.zing.zalo.ui.maintab.widget.CustomMainTab$TabMainView";
    private static final Set<View> PRESS_STYLED = Collections.newSetFromMap(
        Collections.synchronizedMap(new WeakHashMap<View, Boolean>()));

    @Override
    public void hook() throws Throwable
    {
        try
        {
            Class<?> frameLayout = Class.forName(FRAME_LAYOUT, false, classLoader);
            Method onMeasure = frameLayout.getDeclaredMethod("onMeasure", int.class, int.class);
            module.hook(onMeasure).intercept(chain ->
            {
                Object self = chain.getThisObject();
                if (Config.getEnableLiquidUI() && self instanceof View)
                    beforeFrameMeasure((View)self);
                return chain.proceed();
            });

            Class<?> mainTab = Class.forName(CUSTOM_MAIN_TAB, false, classLoader);
            var constructor = mainTab.getDeclaredConstructor(Context.class, AttributeSet.class);
            module.hook(constructor).intercept(chain ->
            {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (Config.getEnableLiquidUI() && self instanceof View)
                    styleMainTab((View)self);
                return result;
            });

            Class<?> tabMainView = Class.forName(TAB_MAIN_VIEW, false, classLoader);
            Method setProgress = tabMainView.getDeclaredMethod("setProgess", float.class);
            module.hook(setProgress).intercept(chain ->
            {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                Object progress = chain.getArg(0);
                if (Config.getEnableLiquidUI() && self instanceof View && progress instanceof Float)
                    styleTabItem((View)self, (Float)progress);
                return result;
            });

            Logger.i("Hooking: LiquidTabHook enabled");
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private static void beforeFrameMeasure(View view)
    {
        if (view == null)
            return;
        try
        {
            if (view instanceof ViewGroup)
            {
                ViewGroup group = (ViewGroup)view;
                group.setClipChildren(false);
                group.setClipToPadding(false);
            }
            Object tab = field(view, "a");
            if (!(tab instanceof View))
                return;
            styleMainTab((View)tab);
            ViewGroup.LayoutParams params = ((View)tab).getLayoutParams();
            if (params != null && params.height > 0)
                setField(view, "d", -params.height);
        }
        catch (Throwable ignored) { }
    }

    private static void styleMainTab(View view)
    {
        if (view == null)
            return;
        try
        {
            Context context = view.getContext();
            if (context == null)
                return;

            view.setOutlineProvider(null);
            view.setElevation(0f);
            view.setTranslationZ(1f);
            view.setClipToOutline(false);

            boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            GradientDrawable shell = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                    night ? 0x70343B44 : 0xC7FFFFFF,
                    night ? 0xA81C2229 : 0xEAF2F7FF
                });
            shell.setShape(GradientDrawable.RECTANGLE);
            shell.setCornerRadius(dp(context, 28));
            shell.setStroke(Math.max(1, dp(context, 1)),
                night ? 0x40FFFFFF : 0x80FFFFFF);
            view.setBackground(shell);
            view.setElevation(dp(context, 10));

            int height = dp(context, 30);
            if (view instanceof ViewGroup)
            {
                ViewGroup group = (ViewGroup)view;
                group.setClipChildren(false);
                group.setClipToPadding(false);
                if (group.getChildCount() > 0)
                {
                    View first = group.getChildAt(0);
                    if (first != null)
                    {
                        first.setVisibility(View.GONE);
                        first.setAlpha(0f);
                    }
                    if (group.getChildCount() > 1)
                    {
                        View second = group.getChildAt(1);
                        if (second != null)
                        {
                            second.setBackground(null);
                            ViewGroup.LayoutParams childParams = second.getLayoutParams();
                            if (childParams != null && childParams.height > 0)
                                height = childParams.height;
                        }
                    }
                }
            }

            int horizontal = dp(context, 18);
            int bottom = dp(context, 10);
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams)
            {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams)params;
                margins.height = height;
                margins.leftMargin = horizontal;
                margins.rightMargin = horizontal;
                margins.bottomMargin = bottom;
            }

            int width = context.getResources().getDisplayMetrics().widthPixels - horizontal - horizontal;
            if (width <= 0)
                return;

            Object currentWidth = field(view, "q");
            if (currentWidth instanceof Integer && (Integer)currentWidth == width)
                return;
            setField(view, "q", width);

            Object tabsObject = field(view, "b");
            if (!(tabsObject instanceof View[]))
                return;
            View[] tabs = (View[])tabsObject;
            if (tabs.length == 0)
                return;

            int itemWidth = (int)(width / (tabs.length + 0.6f));
            int selectedWidth = width - (tabs.length - 1) * itemWidth;
            setField(view, "c", itemWidth);
            setField(view, "d", selectedWidth);

            for (View tab : tabs)
            {
                if (tab == null)
                    continue;
                boolean selected = false;
                if (tab instanceof ViewGroup)
                {
                    ViewGroup group = (ViewGroup)tab;
                    if (group.getChildCount() > 0)
                    {
                        View indicator = group.getChildAt(0);
                        selected = indicator != null && indicator.getVisibility() == View.VISIBLE;
                    }
                }
                ViewGroup.LayoutParams tabParams = tab.getLayoutParams();
                if (tabParams != null)
                    tabParams.width = selected ? selectedWidth : itemWidth;
                styleTabItem(tab, selected ? 1f : 0f);
            }
        }
        catch (Throwable ignored) { }
    }

    private static void styleTabItem(View view, float progress)
    {
        if (view == null)
            return;
        installPressAnimation(view);
        if (progress > 0.15f)
        {
            if (progress > 1f)
                progress = 1f;
            int alpha = (int)(progress * 255f);
            Context context = view.getContext();
            boolean night = context != null
                && (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                    night ? 0xD92AABEE : 0xE62AABEE,
                    night ? 0xD9229ED9 : 0xE6229ED9
                });
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setCornerRadius(dp(context, 100));
            shape.setStroke(Math.max(1, dp(context, 1)), 0xA6FFFFFF);
            InsetDrawable inset = new InsetDrawable(shape, dp(context, 3));
            inset.setAlpha(alpha);
            view.setBackground(inset);
            view.setElevation(dp(context, 4));
            view.setTranslationZ(2f);
        }
        else
        {
            if (view.getBackground() != null)
                view.setBackground(null);
            view.setElevation(0f);
        }
    }

    private static void installPressAnimation(View view)
    {
        if (view == null || !PRESS_STYLED.add(view))
            return;
        view.setOnTouchListener((target, event) ->
        {
            switch (event.getActionMasked())
            {
                case MotionEvent.ACTION_DOWN:
                    target.animate().scaleX(0.94f).scaleY(0.94f)
                        .setDuration(110).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    target.animate().scaleX(1f).scaleY(1f)
                        .setDuration(170).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private static GradientDrawable round(int color, int radius, int strokeColor, int strokeWidth)
    {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private static int dp(Context context, int value)
    {
        if (context == null)
            return value;
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static Object field(Object host, String name)
    {
        Class<?> current = host.getClass();
        while (current != null && current != Object.class)
        {
            try
            {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(host);
            }
            catch (NoSuchFieldException ignored)
            {
                current = current.getSuperclass();
            }
            catch (Throwable ignored)
            {
                return null;
            }
        }
        return null;
    }

    private static void setField(Object host, String name, Object value)
    {
        Class<?> current = host.getClass();
        while (current != null && current != Object.class)
        {
            try
            {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                if (field.getType() == int.class && value instanceof Integer)
                    field.setInt(host, (Integer)value);
                else
                    field.set(host, value);
                return;
            }
            catch (NoSuchFieldException ignored)
            {
                current = current.getSuperclass();
            }
            catch (Throwable ignored)
            {
                return;
            }
        }
    }
}
