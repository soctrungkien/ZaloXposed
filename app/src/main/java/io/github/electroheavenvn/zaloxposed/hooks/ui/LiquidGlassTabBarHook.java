package io.github.electroheavenvn.zaloxposed.hooks.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.DexKitCache;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.ThemeColors;

/**
 * Single-Pass Real-Backdrop GPU Liquid Glass Bottom TabBar (v15):
 * - Captures live Zalo views & wallpaper underneath via hardware RenderNode (zero-copy GPU):
 *   1. TabBar capsule uses crystal-clear frosted backdrop + subtle peripheral rim refraction.
 *   2. Liquid Glass lens refracts BOTH inside the TabBar AND in the protruding top/bottom/side
 *      regions over real Zalo chat rows, avatars, and wallpapers with 3-channel Cauchy RGB dispersion.
 * - Soft luminous white touch glow & gentle spring-physics follow/tilt anywhere inside the TabBar.
 * - Scroll-to-minimize: swiping down through chats fades out tab labels, glides icons smoothly
 *   to the vertical center of the capsule, and compacts the pill.
 */
public class LiquidGlassTabBarHook extends BaseHook
{
    private static final String CUSTOM_TABBAR_TAG = "dx_liquid_glass_tabbar_host";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<FrameLayout, TabBarReplacement> ATTACHED = new WeakHashMap<>();
    private static WeakReference<FrameLayout> lastMainRoot = new WeakReference<>(null);
    private static volatile boolean isCapturingBackdrop = false;

    @Override
    public void installHooks() throws Throwable
    {
        Config.addOnConfigChangedListener((key, oldValue, newValue) ->
        {
            if (Config.KEY_ENABLE_LIQUID_GLASS_TABBAR.equals(key))
            {
                MAIN_HANDLER.post(() ->
                {
                    boolean enabled = Config.getEnableLiquidGlassTabBar();
                    if (enabled)
                    {
                        FrameLayout root = lastMainRoot.get();
                        if (root != null)
                            scheduleAttach(root);
                    }
                    else
                    {
                        for (TabBarReplacement rep : new ArrayList<>(ATTACHED.values()))
                        {
                            if (rep != null)
                                rep.detach();
                        }
                        ATTACHED.clear();
                    }
                });
            }
            else if (Config.KEY_LIQUID_GLASS_THEME.equals(key))
            {
                MAIN_HANDLER.post(() ->
                {
                    for (TabBarReplacement rep : ATTACHED.values())
                    {
                        if (rep != null)
                            rep.refreshTheme();
                    }
                });
            }
        });

        // Observe vertical scroll gestures in chat/content area for smooth scroll-to-minimize
        try
        {
            Method dispatchTouch = Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            doHook(dispatchTouch, 5, chain ->
            {
                Object arg = chain.getArg(0);
                if (arg instanceof MotionEvent ev && Config.getEnableLiquidGlassTabBar())
                {
                    for (TabBarReplacement rep : ATTACHED.values())
                    {
                        if (rep != null)
                            rep.onActivityTouchEvent(ev);
                    }
                }
                return chain.proceed();
            });
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }

        Class<?> mainTabViewCls = Class.forName("com.zing.zalo.ui.maintab.MainTabView", false, classLoader);
        List<Method> methods = DexKitCache.GetMethods("LiquidGlassTabBar_MainTabView_OnCreateView", () -> bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .declaredClass(mainTabViewCls)
                .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                .returnType("android.view.View")
                .paramCount(3)
                .paramTypes("android.view.LayoutInflater", "android.view.ViewGroup", "android.os.Bundle")
                .addUsingString("onCreateView", StringMatchType.Equals)
            )), classLoader);

        if (methods.isEmpty())
        {
            Logger.e("LiquidGlassTabBarHook: MainTabView.onCreateView not found");
            return;
        }

        for (Method method : methods)
        {
            doHook(method, chain ->
            {
                Object result = chain.proceed();
                if (result instanceof FrameLayout root)
                {
                    lastMainRoot = new WeakReference<>(root);
                    if (Config.getEnableLiquidGlassTabBar())
                        scheduleAttach(root);
                }
                return result;
            });
        }
    }

    private static void scheduleAttach(FrameLayout root)
    {
        Runnable task = new Runnable()
        {
            int attempts = 0;

            @Override
            public void run()
            {
                if (!Config.getEnableLiquidGlassTabBar())
                    return;
                if ((!root.isAttachedToWindow() || root.getWidth() <= 0 || root.getHeight() <= 0) && attempts < 20)
                {
                    attempts++;
                    MAIN_HANDLER.postDelayed(this, 120);
                    return;
                }
                if (ATTACHED.containsKey(root))
                    return;

                // Keep the heuristic for the actual tab strip (MainTabFrameLayout
                // is only an outer container and may not hold the tab children).
                // The concrete Zalo frame is still hidden separately.
                ViewGroup origTabBar = findBottomTabBar(root);
                if (origTabBar != null)
                {
                    TabBarReplacement rep = new TabBarReplacement(root, origTabBar);
                    if (rep.attach())
                    {
                        ATTACHED.put(root, rep);
                        return;
                    }
                }
                if (attempts < 25)
                {
                    attempts++;
                    MAIN_HANDLER.postDelayed(this, 150);
                }
            }
        };
        root.post(task);
    }

    /** Finds Zalo's stock com.zing.zalo.uicontrol.MainTabFrameLayout by class name. */
    private static ViewGroup findMainTabFrameLayout(View view)
    {
        if (view == null)
            return null;
        String name = view.getClass().getName();
        if ("com.zing.zalo.uicontrol.MainTabFrameLayout".equals(name)
            || name.endsWith(".MainTabFrameLayout"))
            return view instanceof ViewGroup group ? group : null;
        if (view instanceof ViewGroup group)
        {
            for (int i = 0; i < group.getChildCount(); i++)
            {
                ViewGroup found = findMainTabFrameLayout(group.getChildAt(i));
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static ViewGroup findBottomTabBar(ViewGroup root)
    {
        int rootW = root.getWidth();
        int rootH = root.getHeight();
        if (rootW <= 0 || rootH <= 0)
            return null;

        float density = root.getResources().getDisplayMetrics().density;
        int minH = Math.round(38f * density);
        int maxH = Math.round(112f * density);

        List<ViewGroup> candidates = new ArrayList<>();
        collectTabBarCandidates(root, root, rootW, rootH, minH, maxH, candidates);
        if (candidates.isEmpty())
            return null;

        ViewGroup best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ViewGroup candidate : candidates)
        {
            int score = scoreCandidate(candidate, root, rootW, rootH);
            if (score > bestScore)
            {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static void collectTabBarCandidates(
        ViewGroup root, ViewGroup node, int rootW, int rootH, int minH, int maxH, List<ViewGroup> out)
    {
        if (node.getVisibility() != View.VISIBLE)
            return;
        if (CUSTOM_TABBAR_TAG.equals(node.getTag()))
            return;

        if (node != root && isLikelyTabBar(node, root, rootW, rootH, minH, maxH))
            out.add(node);

        int count = node.getChildCount();
        for (int i = 0; i < count; i++)
        {
            View child = node.getChildAt(i);
            if (child instanceof ViewGroup childGroup)
                collectTabBarCandidates(root, childGroup, rootW, rootH, minH, maxH, out);
        }
    }

    private static boolean isLikelyTabBar(ViewGroup vg, ViewGroup root, int rootW, int rootH, int minH, int maxH)
    {
        int w = vg.getWidth();
        int h = vg.getHeight();
        if (w < rootW * 0.78f || h < minH || h > maxH)
            return false;

        int[] vgLoc = new int[2];
        int[] rootLoc = new int[2];
        vg.getLocationInWindow(vgLoc);
        root.getLocationInWindow(rootLoc);
        int relTop = vgLoc[1] - rootLoc[1];
        if (relTop < rootH * 0.74f)
            return false;

        List<View> visibleChildren = getVisibleChildren(vg);
        if (visibleChildren.size() < 2 || visibleChildren.size() > 7)
            return false;

        int prevCenterX = Integer.MIN_VALUE;
        for (View child : visibleChildren)
        {
            if (child.getWidth() <= 0 || child.getHeight() <= 0)
                return false;
            int centerX = child.getLeft() + child.getWidth() / 2;
            if (centerX <= prevCenterX)
                return false;
            prevCenterX = centerX;
        }
        return true;
    }

    private static int scoreCandidate(ViewGroup vg, ViewGroup root, int rootW, int rootH)
    {
        int score = 0;
        String clsName = vg.getClass().getName().toLowerCase();
        if (clsName.contains("tab") || clsName.contains("bottom"))
            score += 40;
        if (vg.getId() != View.NO_ID)
        {
            try
            {
                String resName = vg.getResources().getResourceEntryName(vg.getId()).toLowerCase();
                if (resName.contains("tab") || resName.contains("bottom") || resName.contains("nav"))
                    score += 50;
            }
            catch (Throwable ignored) { }
        }
        List<View> tabs = getVisibleChildren(vg);
        if (tabs.size() >= 3 && tabs.size() <= 6)
            score += 30;
        for (View child : tabs)
        {
            if (child.isClickable())
                score += 6;
            if (child instanceof ViewGroup)
                score += 4;
        }
        int[] vgLoc = new int[2];
        int[] rootLoc = new int[2];
        vg.getLocationInWindow(vgLoc);
        root.getLocationInWindow(rootLoc);
        int relBottom = (vgLoc[1] - rootLoc[1]) + vg.getHeight();
        if (relBottom >= rootH - 180)
            score += 20;
        return score;
    }

    private static List<View> getVisibleChildren(ViewGroup vg)
    {
        List<View> list = new ArrayList<>();
        int count = vg.getChildCount();
        for (int i = 0; i < count; i++)
        {
            View c = vg.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE && c.getWidth() > 0)
                list.add(c);
        }
        return list;
    }

    private enum TabKind
    {
        MESSAGES,
        CONTACTS,
        DISCOVERY,
        TIMELINE,
        PROFILE,
        GROUPS,
        MORE
    }

    private static final class TabItemInfo
    {
        final View originalView;
        final String label;
        final TabKind kind;
        String badgeText = "";

        TabItemInfo(View originalView, String label, TabKind kind)
        {
            this.originalView = originalView;
            this.label = label;
            this.kind = kind;
        }
    }

    private static final class TabBarReplacement
    {
        private final FrameLayout root;
        private final ViewGroup origTabBar;
        private final List<TabItemInfo> tabs = new ArrayList<>();

        private int origTabBarVisibility;
        private int origTabBarHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
        private int origTabBarMinHeight = 0;
        private android.graphics.drawable.Drawable origTabBarBg;
        private View hiddenDivider;
        private View mainTabFrameLayout;
        private int origDividerHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
        private ViewGroup origWrapperBar;
        private int origWrapperVisibility = View.VISIBLE;
        private int origWrapperHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
        private int origWrapperMinHeight = 0;
        private android.graphics.drawable.Drawable origWrapperBg;
        private LiquidGlassTabBarView customBar;
        private boolean attached = false;
        private boolean isDarkTheme = false;
        private long lastUserSelectMs = 0L;

        // Scroll-to-minimize state
        private float scrollDownY = 0f;
        private float lastScrollY = 0f;
        private boolean trackingScroll = false;

        private final ViewTreeObserver.OnPreDrawListener preDrawListener;

        private final Runnable syncPoller = new Runnable()
        {
            @Override
            public void run()
            {
                if (!attached || customBar == null)
                    return;
                syncStateFromOriginal();
                MAIN_HANDLER.postDelayed(this, 180);
            }
        };

        TabBarReplacement(FrameLayout root, ViewGroup origTabBar)
        {
            this.root = root;
            this.origTabBar = origTabBar;
            this.mainTabFrameLayout = findMainTabFrameLayout(root);
            this.preDrawListener = () ->
            {
                if (!attached)
                    return true;
                // Zalo can re-show the stock tab bar on resume/config changes.
                // Force it back to GONE cheaply (only writes when it actually changed).
                if (origTabBar != null && origTabBar.getVisibility() != View.GONE)
                {
                    origTabBar.setVisibility(View.GONE);
                    origTabBar.setAlpha(0f);
                    origTabBar.setTranslationY(10000f);
                }
                if (origWrapperBar != null && origWrapperBar.getVisibility() != View.GONE)
                {
                    origWrapperBar.setVisibility(View.GONE);
                    origWrapperBar.setAlpha(0f);
                    origWrapperBar.setTranslationY(10000f);
                }
                if (mainTabFrameLayout != null && mainTabFrameLayout.getVisibility() != View.GONE)
                {
                    mainTabFrameLayout.setVisibility(View.GONE);
                    mainTabFrameLayout.setAlpha(0f);
                    mainTabFrameLayout.setTranslationY(10000f);
                }
                return true;
            };
        }

        void onActivityTouchEvent(MotionEvent ev)
        {
            if (!attached || customBar == null || !root.isAttachedToWindow())
                return;
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN)
            {
                int[] barLoc = new int[2];
                customBar.getLocationInWindow(barLoc);
                if (ev.getRawY() < barLoc[1])
                {
                    trackingScroll = true;
                    scrollDownY = ev.getRawY();
                    lastScrollY = scrollDownY;
                }
                else
                {
                    trackingScroll = false;
                }
            }
            else if (action == MotionEvent.ACTION_MOVE && trackingScroll)
            {
                float curY = ev.getRawY();
                float dy = curY - lastScrollY;
                float totalDy = curY - scrollDownY;
                lastScrollY = curY;
                float threshold = 10f * root.getResources().getDisplayMetrics().density;
                if (totalDy < -threshold * 1.5f && dy < -2f)
                {
                    customBar.setCollapsed(true);
                }
                else if (totalDy > threshold * 1.5f && dy > 2f)
                {
                    customBar.setCollapsed(false);
                }
            }
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
            {
                trackingScroll = false;
            }
        }

        boolean attach()
        {
            if (attached)
                return true;

            List<View> rawTabs = getVisibleChildren(origTabBar);
            if (rawTabs.size() < 2)
                return false;

            isDarkTheme = Config.getLiquidGlassTheme() == 1;

            tabs.clear();
            for (int i = 0; i < rawTabs.size(); i++)
            {
                View child = rawTabs.get(i);
                String extracted = extractLabel(child);
                TabKind kind = inferTabKind(extracted, i, rawTabs.size());
                String label = (extracted != null && !extracted.isEmpty()) ? extracted : defaultLabelForKind(kind);
                tabs.add(new TabItemInfo(child, label, kind));
            }

            origTabBarVisibility = origTabBar.getVisibility();
            hideOriginalBar();

            View existing = root.findViewWithTag(CUSTOM_TABBAR_TAG);
            if (existing != null && existing.getParent() instanceof ViewGroup p)
                p.removeView(existing);

            customBar = new LiquidGlassTabBarView(root.getContext(), this);
            customBar.setTag(CUSTOM_TABBAR_TAG);

            float density = root.getResources().getDisplayMetrics().density;
            int sideMargin = Math.round(14f * density);
            int barHeight = Math.round(56f * density);
            int overflow = Math.round(28f * density);
            int bottomInset = getNavigationBarBottomInset(root);
            int bottomMargin = Math.max(bottomInset + Math.round(6f * density), Math.round(10f * density));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeight + overflow * 2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.leftMargin = Math.max(0, sideMargin - overflow);
            lp.rightMargin = Math.max(0, sideMargin - overflow);
            lp.bottomMargin = Math.max(0, bottomMargin - overflow);

            root.setClipChildren(false);
            root.setClipToPadding(false);
            root.addView(customBar, lp);
            customBar.bringToFront();

            attached = true;
            syncStateFromOriginal();
            customBar.snapImmediately();
            try { root.getViewTreeObserver().addOnPreDrawListener(preDrawListener); } catch (Throwable ignored) { }
            MAIN_HANDLER.postDelayed(syncPoller, 220);
            return true;
        }

        void refreshTheme()
        {
            isDarkTheme = Config.getLiquidGlassTheme() == 1;
            if (customBar != null)
                customBar.invalidate();
        }

        void detach()
        {
            if (!attached)
                return;
            attached = false;
            try { root.getViewTreeObserver().removeOnPreDrawListener(preDrawListener); } catch (Throwable ignored) { }
            MAIN_HANDLER.removeCallbacks(syncPoller);

            if (customBar != null && customBar.getParent() instanceof ViewGroup p)
                p.removeView(customBar);
            customBar = null;

            restoreViewCompletely(origTabBar, origTabBarVisibility, origTabBarHeight, origTabBarMinHeight, origTabBarBg);
            if (origWrapperBar != null)
            {
                restoreViewCompletely(origWrapperBar, origWrapperVisibility, origWrapperHeight, origWrapperMinHeight, origWrapperBg);
                origWrapperBar = null;
            }
            if (hiddenDivider != null)
            {
                restoreViewCompletely(hiddenDivider, View.VISIBLE, origDividerHeight, 0, hiddenDivider.getBackground());
                hiddenDivider = null;
            }
            root.requestLayout();
        }

        private static void restoreViewCompletely(
            View v, int vis, int origHeight, int origMinH, android.graphics.drawable.Drawable origBg)
        {
            if (v == null)
                return;
            v.setVisibility(vis);
            v.setAlpha(1f);
            v.setTranslationY(0f);
            v.setMinimumHeight(origMinH);
            if (origBg != null)
                v.setBackground(origBg);
            v.setEnabled(true);
            v.setClickable(true);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.height != origHeight)
            {
                lp.height = origHeight;
                v.setLayoutParams(lp);
            }
        }

        private static void collapseViewCompletely(View v)
        {
            if (v == null)
                return;
            if (v.getVisibility() == View.GONE && v.getAlpha() == 0f
                && v.getTranslationY() == 10000f && (!v.isEnabled()) && (!v.isClickable()))
                return;
            v.setVisibility(View.GONE);
            v.setAlpha(0f);
            v.setBackground(null);
            v.setEnabled(false);
            v.setClickable(false);
            v.setMinimumHeight(0);
            v.setPadding(0, 0, 0, 0);
            v.setTranslationY(10000f);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.height != 0)
            {
                lp.height = 0;
                v.setLayoutParams(lp);
            }
        }

        private void expandContentToBottom(ViewGroup container, int maxReservePx, int depth)
        {
            if (container == null || depth > 2)
                return;
            int count = container.getChildCount();
            for (int i = 0; i < count; i++)
            {
                View child = container.getChildAt(i);
                if (child == customBar || child == origTabBar || child == origWrapperBar || child == hiddenDivider)
                    continue;
                if (child instanceof ViewGroup vg)
                {
                    vg.setClipToPadding(false);
                    if (depth < 2)
                        expandContentToBottom(vg, maxReservePx, depth + 1);
                }
                if (child.getPaddingBottom() > 0 && child.getPaddingBottom() <= maxReservePx)
                    child.setPadding(child.getPaddingLeft(), child.getPaddingTop(), child.getPaddingRight(), 0);
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (lp instanceof ViewGroup.MarginLayoutParams mlp && mlp.bottomMargin > 0 && mlp.bottomMargin <= maxReservePx)
                {
                    mlp.bottomMargin = 0;
                    child.setLayoutParams(lp);
                }
                if (lp instanceof RelativeLayout.LayoutParams rlp)
                {
                    int aboveId = rlp.getRule(RelativeLayout.ABOVE);
                    if (aboveId != 0 && ((origTabBar != null && aboveId == origTabBar.getId())
                        || (origWrapperBar != null && aboveId == origWrapperBar.getId())))
                    {
                        rlp.removeRule(RelativeLayout.ABOVE);
                        rlp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                        child.setLayoutParams(rlp);
                    }
                }
                if (lp != null && lp.getClass().getName().contains("ConstraintLayout"))
                {
                    try
                    {
                        java.lang.reflect.Field f = lp.getClass().getField("goneBottomMargin");
                        if (f.getInt(lp) > 0)
                        {
                            f.setInt(lp, 0);
                            child.setLayoutParams(lp);
                        }
                    }
                    catch (Throwable ignored) { }
                }
            }
        }

        private void hideOriginalBar()
        {
            origTabBarHeight = origTabBar.getLayoutParams() != null ? origTabBar.getLayoutParams().height : ViewGroup.LayoutParams.WRAP_CONTENT;
            origTabBarMinHeight = origTabBar.getMinimumHeight();
            origTabBarBg = origTabBar.getBackground();
            collapseViewCompletely(origTabBar);
            if (mainTabFrameLayout != null && mainTabFrameLayout != origTabBar)
                collapseViewCompletely(mainTabFrameLayout);
            ViewParent parent = origTabBar.getParent();
            if (parent instanceof ViewGroup parentVg && parentVg != root)
            {
                float density = root.getResources().getDisplayMetrics().density;
                int idx = parentVg.indexOfChild(origTabBar);
                if (idx > 0)
                {
                    View prev = parentVg.getChildAt(idx - 1);
                    if (prev != null && prev.getHeight() > 0 && prev.getHeight() <= Math.round(3f * density))
                    {
                        hiddenDivider = prev;
                        origDividerHeight = prev.getLayoutParams() != null ? prev.getLayoutParams().height : ViewGroup.LayoutParams.WRAP_CONTENT;
                        collapseViewCompletely(prev);
                    }
                }
                if (parentVg.getHeight() > 0 && parentVg.getHeight() <= Math.round(115f * density))
                {
                    origWrapperBar = parentVg;
                    origWrapperVisibility = parentVg.getVisibility();
                    origWrapperHeight = parentVg.getLayoutParams() != null ? parentVg.getLayoutParams().height : ViewGroup.LayoutParams.WRAP_CONTENT;
                    origWrapperMinHeight = parentVg.getMinimumHeight();
                    origWrapperBg = parentVg.getBackground();
                    collapseViewCompletely(parentVg);
                }
            }
            int maxReserve = Math.round(115f * root.getResources().getDisplayMetrics().density);
            expandContentToBottom(root, maxReserve, 0);
            if (parent instanceof ViewGroup pVg && pVg != root)
                expandContentToBottom(pVg, maxReserve, 0);
            root.requestLayout();
        }

        void selectTab(int index)
        {
            if (index < 0 || index >= tabs.size())
                return;
            lastUserSelectMs = SystemClock.uptimeMillis();
            View target = tabs.get(index).originalView;
            View clickable = findClickableTarget(target);
            View clickTarget = (clickable != null) ? clickable : target;
            try
            {
                clickTarget.callOnClick();
            }
            catch (Throwable ignored)
            {
                clickTarget.performClick();
            }
        }

        private View findClickableTarget(View v)
        {
            if (v.isClickable() && v.hasOnClickListeners())
                return v;
            if (v instanceof ViewGroup vg)
            {
                for (int i = 0; i < vg.getChildCount(); i++)
                {
                    View found = findClickableTarget(vg.getChildAt(i));
                    if (found != null)
                        return found;
                }
            }
            return v.isClickable() ? v : null;
        }

        private void syncStateFromOriginal()
        {
            if (customBar == null)
                return;
            for (TabItemInfo info : tabs)
                updateBadgeInfo(info, info.originalView);

            long elapsed = SystemClock.uptimeMillis() - lastUserSelectMs;
            if (elapsed >= 700L)
            {
                int selectedIdx = -1;
                int singleLabelIdx = -1;
                int visibleLabelCount = 0;

                for (int i = 0; i < tabs.size(); i++)
                {
                    View v = tabs.get(i).originalView;
                    if (selectedIdx < 0 && isSubtreeSelected(v))
                        selectedIdx = i;
                    if (hasNonBadgeText(v))
                    {
                        singleLabelIdx = i;
                        visibleLabelCount++;
                    }
                }

                if (selectedIdx < 0 && visibleLabelCount == 1)
                    selectedIdx = singleLabelIdx;

                if (selectedIdx >= 0)
                    customBar.onExternalTabSelected(selectedIdx);
            }
            else
            {
                customBar.invalidate();
            }

            if (origTabBar.getVisibility() != View.GONE || origTabBar.getHeight() > 0)
                collapseViewCompletely(origTabBar);
            if (origWrapperBar != null && (origWrapperBar.getVisibility() != View.GONE || origWrapperBar.getHeight() > 0))
                collapseViewCompletely(origWrapperBar);
            int maxReserve = Math.round(115f * root.getResources().getDisplayMetrics().density);
            expandContentToBottom(root, maxReserve, 0);
        }

        private static TextView findAnyTextView(View v)
        {
            if (v instanceof TextView tv && tv.getText() != null && tv.getText().length() > 0)
            {
                String s = tv.getText().toString().trim();
                if (!s.matches("^[0-9+]+$") && !s.equals("N"))
                    return tv;
            }
            if (v instanceof ViewGroup vg)
            {
                for (int i = 0; i < vg.getChildCount(); i++)
                {
                    TextView found = findAnyTextView(vg.getChildAt(i));
                    if (found != null)
                        return found;
                }
            }
            return null;
        }

        private static String extractLabel(View v)
        {
            CharSequence cd = v.getContentDescription();
            if (cd != null && cd.length() > 0)
                return cd.toString().trim();
            TextView tv = findAnyTextView(v);
            if (tv != null && tv.getText() != null)
                return tv.getText().toString().trim();
            return "";
        }

        private static TabKind inferTabKind(String label, int index, int total)
        {
            String low = label != null ? label.toLowerCase() : "";
            if (low.contains("tin nh") || low.contains("message") || low.contains("chat"))
                return TabKind.MESSAGES;
            if (low.contains("danh b") || low.contains("contact"))
                return TabKind.CONTACTS;
            if (low.contains("khÃ¡m ph") || low.contains("discover"))
                return TabKind.DISCOVERY;
            if (low.contains("nháº­t k") || low.contains("tÆ°á»ng") || low.contains("timeline") || low.contains("diary"))
                return TabKind.TIMELINE;
            if (low.contains("cÃ¡ nhÃ¢n") || low.contains("me") || low.contains("profile"))
                return TabKind.PROFILE;
            if (low.contains("nhÃ³m") || low.contains("group"))
                return TabKind.GROUPS;
            if (low.contains("thÃªm") || low.contains("more"))
                return TabKind.MORE;

            if (total == 5)
            {
                return switch (index)
                {
                    case 0 -> TabKind.MESSAGES;
                    case 1 -> TabKind.CONTACTS;
                    case 2 -> TabKind.DISCOVERY;
                    case 3 -> TabKind.TIMELINE;
                    default -> TabKind.PROFILE;
                };
            }
            if (total == 4)
            {
                return switch (index)
                {
                    case 0 -> TabKind.MESSAGES;
                    case 1 -> TabKind.CONTACTS;
                    case 2 -> TabKind.TIMELINE;
                    default -> TabKind.PROFILE;
                };
            }
            if (index == 0)
                return TabKind.MESSAGES;
            if (index == total - 1)
                return TabKind.PROFILE;
            return switch (index)
            {
                case 1 -> TabKind.CONTACTS;
                case 2 -> TabKind.DISCOVERY;
                case 3 -> TabKind.TIMELINE;
                default -> TabKind.MORE;
            };
        }

        private static String defaultLabelForKind(TabKind kind)
        {
            return switch (kind)
            {
                case MESSAGES -> "Tin nháº¯n";
                case CONTACTS -> "Danh báº¡";
                case DISCOVERY -> "KhÃ¡m phÃ¡";
                case TIMELINE -> "Nháº­t kÃ½";
                case PROFILE -> "CÃ¡ nhÃ¢n";
                case GROUPS -> "NhÃ³m";
                case MORE -> "ThÃªm";
            };
        }

        private static boolean isSubtreeSelected(View v)
        {
            if (v.isSelected() || v.isActivated())
                return true;
            if (v instanceof ViewGroup vg)
            {
                for (int i = 0; i < vg.getChildCount(); i++)
                {
                    if (isSubtreeSelected(vg.getChildAt(i)))
                        return true;
                }
            }
            return false;
        }

        private static boolean hasNonBadgeText(View v)
        {
            if (v.getVisibility() != View.VISIBLE)
                return false;
            if (v instanceof TextView tv && tv.getText() != null && tv.getText().length() > 0 && tv.getAlpha() > 0.2f)
            {
                String s = tv.getText().toString().trim();
                if (!s.matches("^[0-9+]+$") && !s.equals("N") && !s.equals("!"))
                    return true;
            }
            if (v instanceof ViewGroup vg)
            {
                for (int i = 0; i < vg.getChildCount(); i++)
                {
                    if (hasNonBadgeText(vg.getChildAt(i)))
                        return true;
                }
            }
            return false;
        }

        private static void updateBadgeInfo(TabItemInfo info, View v)
        {
            info.badgeText = "";
            scanBadge(info, v);
        }

        private static void scanBadge(TabItemInfo info, View v)
        {
            if (v.getVisibility() != View.VISIBLE)
                return;
            if (v instanceof TextView tv && tv.getText() != null)
            {
                String s = tv.getText().toString().trim();
                if (!s.isEmpty() && (s.matches("^[0-9+]+$") || s.equals("N") || s.equals("!")))
                {
                    info.badgeText = s;
                    return;
                }
            }
            if (v instanceof ViewGroup vg)
            {
                for (int i = 0; i < vg.getChildCount(); i++)
                    scanBadge(info, vg.getChildAt(i));
            }
        }

        private static int getNavigationBarBottomInset(View view)
        {
            try
            {
                WindowInsets insets = view.getRootWindowInsets();
                if (insets != null)
                {
                    if (Build.VERSION.SDK_INT >= 30)
                        return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                    else
                        return insets.getSystemWindowInsetBottom();
                }
            }
            catch (Throwable ignored) { }
            return 0;
        }
    }

    @SuppressLint("ViewConstructor")
    private static final class LiquidGlassTabBarView extends View
    {
        /** V6 damped spring - keeps velocity when the target changes direction. */
        private static final class Spring
        {
            private float value, target, velocity;
            private float stiffness, dampingRatio;

            Spring(float initial, float k, float damping)
            {
                value = target = initial;
                stiffness = k;
                dampingRatio = damping;
            }

            void animateTo(float next) { target = next; }

            void snapTo(float next)
            {
                value = target = next;
                velocity = 0f;
            }

            boolean update(float seconds)
            {
                float dt = clamp(seconds, 0.001f, 0.034f);
                int steps = Math.max(1, (int)Math.ceil(dt / 0.008f));
                float h = dt / steps;
                float damping = 2f * dampingRatio * (float)Math.sqrt(stiffness);
                for (int i = 0; i < steps; i++)
                {
                    velocity += (-stiffness * (value - target) - damping * velocity) * h;
                    value += velocity * h;
                }
                if (Math.abs(target - value) < 0.0015f && Math.abs(velocity) < 0.008f)
                {
                    value = target;
                    velocity = 0f;
                    return false;
                }
                return true;
            }

            float value() { return value; }
            float velocity() { return velocity; }
            float targetOf() { return target; }
        }

        // Real-Backdrop Single-Pass Optical Liquid Glass Shader (v15):
        // - Refracts live Zalo views & wallpaper underneath BOTH along the TabBar capsule bevel
        //   AND across the full protruding Liquid Glass lens (with 3-channel Cauchy RGB dispersion).
        // - Strictly limits active blue ink tinting inside uBarRect so background Zalo chat text
        //   underneath the protruding lens edges keeps its true natural colors.
        private static final String LIQUID_GLASS_V15_AGSL =
            "uniform shader content;" +
            "uniform float4 uBarRect;" +
            "uniform float uBarRadius;" +
            "uniform float4 uRect;" +
            "uniform float uRadius;" +
            "uniform float uInflate;" +
            "uniform float uDensity;" +
            "uniform float uDark;" +
            "uniform float3 uAccent;" +
            "float sdRoundBox(float2 p, float2 b, float r) {" +
            "  float2 q = abs(p) - b + r;" +
            "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;" +
            "}" +
            "float2 sdGrad(float2 p, float2 b, float r) {" +
            "  float e = 0.85;" +
            "  float gx = sdRoundBox(p + float2(e, 0.0), b, r) - sdRoundBox(p - float2(e, 0.0), b, r);" +
            "  float gy = sdRoundBox(p + float2(0.0, e), b, r) - sdRoundBox(p - float2(0.0, e), b, r);" +
            "  return normalize(float2(gx, gy) + float2(0.00001));" +
            "}" +
            "float3 tintTabInkIfInsideBar(half4 px, float2 coord, float isDarkMode) {" +
            "  float a = clamp(float(px.a), 0.0, 1.0);" +
            "  float3 col = (a > 0.004) ? (float3(px.rgb) / max(a, 0.004)) : float3(0.0);" +
            "  float2 barC = (uBarRect.xy + uBarRect.zw) * 0.5;" +
            "  float2 barH = (uBarRect.zw - uBarRect.xy) * 0.5;" +
            "  float dBar = sdRoundBox(coord - barC, barH, uBarRadius);" +
            "  if (dBar > 0.5) return col;" +
            "  float mx = max(col.r, max(col.g, col.b));" +
            "  float mn = min(col.r, min(col.g, col.b));" +
            "  float sat = (mx - mn) / max(mx, 0.001);" +
            "  float luma = dot(col, float3(0.299, 0.587, 0.114));" +
            "  float inkLight = smoothstep(0.32, 0.08, luma) * (1.0 - smoothstep(0.16, 0.30, sat));" +
            "  float inkDark  = smoothstep(0.68, 0.92, luma) * (1.0 - smoothstep(0.16, 0.30, sat));" +
            "  float inkMask = mix(inkLight, inkDark, isDarkMode);" +
            "  float3 activeBlue = uAccent;" +
            "  return mix(col, activeBlue, inkMask);" +
            "}" +
            "half4 evalBarCapsule(float2 fragCoord, float2 barC, float2 barH, float dBar) {" +
            "  if (dBar <= 0.0 && dBar > -8.5 * uDensity) {" +
            "    float ub = clamp(-dBar / (8.5 * uDensity), 0.0, 1.0);" +
            "    float bCurve = pow(1.0 - ub, 2.6);" +
            "    float2 gBar = sdGrad(fragCoord - barC, barH, uBarRadius);" +
            "    float bMag = bCurve * (5.5 * uDensity);" +
            "    half4 bR = content.eval(fragCoord - gBar * (bMag * 0.78));" +
            "    half4 bG = content.eval(fragCoord - gBar * bMag);" +
            "    half4 bB = content.eval(fragCoord - gBar * (bMag * 1.24));" +
            "    float nyB = (fragCoord.y - barC.y) / max(barH.y, 1.0);" +
            "    float bRim = (1.0 - smoothstep(0.0, 1.1 * uDensity, -dBar)) * (0.28 + 0.16 * max(0.0, -nyB));" +
            "    half3 rgb = clamp(half3(bR.r, bG.g, bB.b) + half3(bRim), half3(0.0), half3(1.0));" +
            "    return half4(rgb, bG.a);" +
            "  }" +
            "  return content.eval(fragCoord);" +
            "}" +
            "half4 main(float2 fragCoord) {" +
            "  float2 barC = (uBarRect.xy + uBarRect.zw) * 0.5;" +
            "  float2 barH = (uBarRect.zw - uBarRect.xy) * 0.5;" +
            "  float dBar = sdRoundBox(fragCoord - barC, barH, uBarRadius);" +
            "  float2 center = (uRect.xy + uRect.zw) * 0.5;" +
            "  float2 halfSize = (uRect.zw - uRect.xy) * 0.5;" +
            "  float2 p = fragCoord - center;" +
            "  float d = sdRoundBox(p, halfSize, uRadius);" +
            "  float barMask = 1.0 - smoothstep(-0.7, 0.7, dBar);" +
            "  half4 basePx = (dBar > 0.8) ? half4(0.0) : (evalBarCapsule(fragCoord, barC, barH, dBar) * half(barMask));" +
            "  if (d > 0.8) return basePx;" +
            "  float mask = 1.0 - smoothstep(-0.7, 0.7, d);" +
            "  if (uInflate <= 0.015) {" +
            "    float3 tCol = tintTabInkIfInsideBar(basePx, fragCoord, uDark);" +
            "    return mix(basePx, half4(half3(tCol * float(basePx.a)), basePx.a), half(mask));" +
            "  }" +
            "  float bevelW = 18.0 * uDensity;" +
            "  float u = clamp(-d / bevelW, 0.0, 1.0);" +
            "  float lensCurve = pow(1.0 - u, 3.6);" +
            "  float2 grad = sdGrad(p, halfSize, uRadius);" +
            "  float refractMag = lensCurve * (18.5 * uDensity) * uInflate;" +
            "  float2 cRCoord = fragCoord - grad * (refractMag * 0.58);" +
            "  float2 cGCoord = fragCoord - grad * (refractMag * 1.00);" +
            "  float2 cBCoord = fragCoord - grad * (refractMag * 1.52);" +
            "  float3 cR = tintTabInkIfInsideBar(content.eval(cRCoord), cRCoord, uDark);" +
            "  float3 cG = tintTabInkIfInsideBar(content.eval(cGCoord), cGCoord, uDark);" +
            "  float3 cB = tintTabInkIfInsideBar(content.eval(cBCoord), cBCoord, uDark);" +
            "  float3 rgb = float3(cR.r, cG.g, cB.b);" +
            "  float fresnel = pow(1.0 - u, 3.8) * uInflate;" +
            "  float outerRim = (1.0 - smoothstep(0.0, 1.1 * uDensity, -d)) * uInflate;" +
            "  float ny = p.y / max(halfSize.y, 1.0);" +
            "  float topSheen = pow(max(0.0, -ny), 2.6) * fresnel;" +
            "  float botSheen = pow(max(0.0,  ny), 2.6) * fresnel;" +
            "  float spec = outerRim * mix(0.55, 0.36, uDark) + (topSheen * 0.24 + botSheen * 0.16);" +
            "  float3 glassRgb = clamp(rgb + float3(spec), 0.0, 1.0);" +
            "  half4 lensPx = half4(half3(glassRgb), half(1.0));" +
            "  return mix(basePx, lensPx, half(mask));" +
            "}";

        private final TabBarReplacement owner;
        private final float density;
        private final float scaledDensity;
        private final int touchSlop;
        private final int overflowPad;

        private final Paint capsuleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint capsuleBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint restPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint innerWhiteGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fallbackRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bottomFadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF baseCapsuleRect = new RectF();
        private final RectF capsuleRect = new RectF();
        private final RectF dropletRect = new RectF();
        private final Path capsuleClipPath = new Path();
        private final Path clipPath = new Path();
        private final Path iconPath = new Path();

        private Object rawBackdropNodeObj;
        private Object frostedBackdropNodeObj;
        private Object unifiedRenderNodeObj;
        private Object runtimeShaderObj;

        // Physics state
        private int activeIndex = 0;
        private int hoveredIndex = 0;
        private float currentTabPos = 0.5f;
        private float targetTabPos = 0.5f;
        private float velocityTab = 0f;
        private float inflateProgress = 0f;
        private float targetInflate = 0f;
        private float stretchX = 0f;

        // Touch glow + subtle follow/tilt across entire TabBar
        private float touchGlowAlpha = 0f;
        private float targetTouchGlow = 0f;
        private float touchGlowX = 0f;
        private float touchGlowY = 0f;
        private float barOffsetX = 0f;
        private float targetBarOffsetX = 0f;
        private float barOffsetY = 0f;
        private float targetBarOffsetY = 0f;
        private float barScale = 1f;
        private float targetBarScale = 1f;
        private float scaleY = 1f;
        private float stretchY = 0f;

        // V6 motion: position 1000/1, press 420/0.90, scaleX 150/0.68,
        // scaleY 175/0.76, stretch 210/0.79.
        private final Spring posSpring = new Spring(0.5f, 1000f, 1f);
        private final Spring pressSpring = new Spring(0f, 420f, 0.90f);
        private final Spring scaleXSpring = new Spring(1f, 150f, 0.68f);
        private final Spring scaleYSpring = new Spring(1f, 175f, 0.76f);
        private final Spring stretchSpring = new Spring(0f, 210f, 0.79f);

        // Scroll-to-minimize state
        private float collapseProgress = 0f;
        private float targetCollapse = 0f;

        private long lastFrameMs = 0L;
        private boolean animating = false;
        // Re-recording the live backdrop every frame is very expensive and
        // makes dragging stutter. Refresh it at most ~8 times per second.
        private long lastBackdropCaptureMs = 0L;
        private boolean backdropRecorded = false;

        // Touch drag state
        private boolean pointerDown = false;
        private boolean isDragging = false;
        private float downX = 0f;
        private float lastTouchX = 0f;
        private long lastTouchMs = 0L;

        LiquidGlassTabBarView(Context context, TabBarReplacement owner)
        {
            super(context);
            this.owner = owner;
            this.density = context.getResources().getDisplayMetrics().density;
            this.scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
            this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            this.overflowPad = dp(28f);

            capsuleBorderPaint.setStyle(Paint.Style.STROKE);
            fallbackRimPaint.setStyle(Paint.Style.STROKE);

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextSize(10.5f * scaledDensity);

            badgePaint.setColor(0xFFFF3B30);
            badgeTextPaint.setColor(Color.WHITE);
            badgeTextPaint.setTextAlign(Paint.Align.CENTER);
            badgeTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
            badgeTextPaint.setTextSize(9f * scaledDensity);

            if (Build.VERSION.SDK_INT >= 33)
            {
                try
                {
                    rawBackdropNodeObj = new RenderNode("DXRawBackdrop");
                    frostedBackdropNodeObj = new RenderNode("DXFrostedBackdrop");
                    unifiedRenderNodeObj = new RenderNode("DXLiquidGlassV15");
                    runtimeShaderObj = new RuntimeShader(LIQUID_GLASS_V15_AGSL);
                }
                catch (Throwable t)
                {
                    Logger.e(t);
                }
            }
        }

        void setCollapsed(boolean collapsed)
        {
            float next = collapsed ? 1f : 0f;
            if (Math.abs(targetCollapse - next) > 0.01f)
            {
                targetCollapse = next;
                startAnimationLoop();
            }
        }

        void snapImmediately()
        {
            updateDynamicCapsuleRect();
            if (getWidth() > 0 && !owner.tabs.isEmpty())
            {
                currentTabPos = activeIndex + 0.5f;
                targetTabPos = currentTabPos;
                velocityTab = 0f;
                inflateProgress = 0f;
                targetInflate = 0f;
                stretchX = 0f;
                touchGlowAlpha = 0f;
                targetTouchGlow = 0f;
                barOffsetX = 0f;
                barOffsetY = 0f;
                barScale = 1f;
            }
            invalidate();
        }

        void onExternalTabSelected(int idx)
        {
            if (isDragging || pointerDown || animating)
                return;
            if (idx < 0 || idx >= owner.tabs.size())
                return;
            if (idx != activeIndex)
            {
                activeIndex = idx;
                hoveredIndex = idx;
                updateTargetForIndex(idx);
                startAnimationLoop();
            }
            else
            {
                invalidate();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh)
        {
            super.onSizeChanged(w, h, oldw, oldh);
            baseCapsuleRect.set(overflowPad, overflowPad, w - overflowPad, h - overflowPad);
            if (Build.VERSION.SDK_INT >= 29)
            {
                if (rawBackdropNodeObj instanceof RenderNode n)
                    n.setPosition(0, 0, w, h);
                if (frostedBackdropNodeObj instanceof RenderNode n)
                    n.setPosition(0, 0, w, h);
                if (unifiedRenderNodeObj instanceof RenderNode n)
                    n.setPosition(0, 0, w, h);
            }
            snapImmediately();
        }

        private void updateDynamicCapsuleRect()
        {
            if (baseCapsuleRect.isEmpty())
                return;
            float shrinkX = dpf(10f) * collapseProgress;
            float shrinkH = dpf(14f) * collapseProgress;
            float shiftDown = dpf(6f) * collapseProgress;

            float cx = baseCapsuleRect.centerX() + barOffsetX;
            float cy = baseCapsuleRect.centerY() + shiftDown + barOffsetY;
            // The dock seen through the droplet contracts slightly, but never
            // more than 1px of total height (0.5px per edge) - avoids a visible
            // height jump when touching.
            float halfW = baseCapsuleRect.width() * 0.5f - shrinkX;
            float baseHalfH = baseCapsuleRect.height() * 0.5f - shrinkH * 0.5f;
            float innerInset = Math.min(baseHalfH * 0.15f * clamp(inflateProgress, 0f, 1f), 0.5f);
            float halfH = baseHalfH - innerInset;

            capsuleRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
            capsuleClipPath.reset();
            float capR = capsuleRect.height() * 0.5f;
            capsuleClipPath.addRoundRect(capsuleRect, capR, capR, Path.Direction.CW);
        }

        private void captureLiveBackdrop(Canvas rc)
        {
            if (isCapturingBackdrop)
                return;
            isCapturingBackdrop = true;
            try
            {
                int w = getWidth();
                int h = getHeight();
                // Keep the backdrop translucent so the real Zalo content shows
                // through instead of a flat white/black plate.
                rc.drawColor(owner.isDarkTheme ? 0x66101418 : 0x33F4F7FC);

                Drawable rootBg = owner.root.getBackground();
                if (rootBg != null)
                {
                    rc.save();
                    rc.translate(-getLeft(), -getTop());
                    rootBg.draw(rc);
                    rc.restore();
                }

                int childCount = owner.root.getChildCount();
                View topContent = null;
                for (int i = childCount - 1; i >= 0; i--)
                {
                    View child = owner.root.getChildAt(i);
                    if (child == this || child == owner.origTabBar || child == owner.origWrapperBar || child == owner.hiddenDivider)
                        continue;
                    if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0.01f)
                        continue;
                    if (child.getWidth() >= owner.root.getWidth() * 0.8f && child.getHeight() >= owner.root.getHeight() * 0.5f)
                    {
                        topContent = child;
                        break;
                    }
                }
                if (topContent != null)
                {
                    float cLeft = topContent.getLeft() + topContent.getTranslationX();
                    float cTop = topContent.getTop() + topContent.getTranslationY();
                    rc.save();
                    rc.translate(cLeft - getLeft(), cTop - getTop());
                    rc.clipRect(0, 0, topContent.getWidth(), topContent.getHeight());
                    topContent.draw(rc);
                    rc.restore();
                }

                // Put the dock material and its thin silver edge into the
                // optical input so the droplet refracts the REAL dock border
                // (detail B), not a flat line drawn on top of the lens.
                if (!capsuleRect.isEmpty())
                {
                    float capR = capsuleRect.height() * 0.5f;
                    Paint dockRim = new Paint(Paint.ANTI_ALIAS_FLAG);
                    dockRim.setStyle(Paint.Style.STROKE);
                    dockRim.setStrokeWidth(0.65f * density);
                    dockRim.setColor(owner.isDarkTheme ? 0x66FFFFFF : 0x99FFFFFF);
                    rc.drawRoundRect(capsuleRect, capR, capR, dockRim);
                }
            }
            catch (Throwable ignored) { }
            finally
            {
                isCapturingBackdrop = false;
            }
        }

        private void drawGlassForeground(Canvas canvas, boolean dark, boolean hasBackdrop)
        {
            float capRadius = capsuleRect.height() * 0.5f;

            // 1. Crystal-clear translucent glass tint (much more transparent so live backdrop shows through!)
            int topGlass = hasBackdrop
                ? (dark ? 0x721C1E22 : 0x78FFFFFF)
                : (dark ? 0xB836383C : 0xB8FFFFFF);
            int botGlass = hasBackdrop
                ? (dark ? 0x62121418 : 0x64F0F4FA)
                : (dark ? 0xA824262A : 0xA8F0F4FA);
            capsuleFillPaint.setShadowLayer(dpf(10f), 0f, dpf(3f), dark ? 0x55000000 : 0x16001433);
            capsuleFillPaint.setShader(new LinearGradient(
                0f, capsuleRect.top, 0f, capsuleRect.bottom, topGlass, botGlass, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(capsuleRect, capRadius, capRadius, capsuleFillPaint);
            capsuleFillPaint.setShader(null);
            capsuleFillPaint.clearShadowLayer();

            // 2. Soft luminous WHITE touch glow anywhere inside TabBar capsule (even outside droplet!)
            if (touchGlowAlpha > 0.01f)
            {
                canvas.save();
                canvas.clipPath(capsuleClipPath);
                float gx = (touchGlowX > 0f) ? touchGlowX : capsuleRect.centerX();
                float gy = (touchGlowY > 0f) ? touchGlowY : capsuleRect.centerY();
                float glowR = Math.max(capsuleRect.height() * 1.45f, dpf(86f));
                int peak = Color.argb((int) ((dark ? 74 : 185) * touchGlowAlpha), 255, 255, 255);
                int mid = Color.argb((int) ((dark ? 28 : 85) * touchGlowAlpha), 255, 255, 255);
                innerWhiteGlowPaint.setShader(new RadialGradient(
                    gx, gy, glowR,
                    new int[] { peak, mid, Color.TRANSPARENT },
                    new float[] { 0.0f, 0.52f, 1.0f },
                    Shader.TileMode.CLAMP));
                canvas.drawRect(capsuleRect, innerWhiteGlowPaint);
                innerWhiteGlowPaint.setShader(null);
                canvas.restore();
            }

            // 3. Crisp 2-layer glass capsule border
            float softSw = dpf(1.8f);
            capsuleBorderPaint.setShader(null);
            capsuleBorderPaint.setStrokeWidth(softSw);
            capsuleBorderPaint.setColor(dark ? 0x36FFFFFF : 0x44A8B8D0);
            RectF softInset = new RectF(
                capsuleRect.left + softSw * 0.5f,
                capsuleRect.top + softSw * 0.5f,
                capsuleRect.right - softSw * 0.5f,
                capsuleRect.bottom - softSw * 0.5f);
            canvas.drawRoundRect(softInset, capRadius, capRadius, capsuleBorderPaint);

            float sw = dpf(0.9f);
            capsuleBorderPaint.setStrokeWidth(sw);
            capsuleBorderPaint.setShader(new LinearGradient(
                0f, capsuleRect.top, 0f, capsuleRect.bottom,
                dark ? new int[] { 0x80FFFFFF, 0x2AFFFFFF, 0x50FFFFFF }
                     : new int[] { 0xF0FFFFFF, 0x34A0B0C8, 0xF2FFFFFF },
                new float[] { 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP));
            RectF borderInset = new RectF(
                capsuleRect.left + sw * 0.5f,
                capsuleRect.top + sw * 0.5f,
                capsuleRect.right - sw * 0.5f,
                capsuleRect.bottom - sw * 0.5f);
            canvas.drawRoundRect(borderInset, capRadius, capRadius, capsuleBorderPaint);

            // 4. Resting subtle pill (fades out cleanly as Liquid Glass lens inflates)
            float restAlpha = clamp(1f - inflateProgress * 4.5f, 0f, 1f);
            if (!dropletRect.isEmpty() && restAlpha > 0.01f)
            {
                float dropR = dropletRect.height() * 0.5f;
                restPillPaint.setColor(dark
                    ? Color.argb((int) (135 * restAlpha), 74, 78, 86)
                    : Color.argb((int) (155 * restAlpha), 214, 222, 234));
                canvas.drawRoundRect(dropletRect, dropR, dropR, restPillPaint);
            }

            // 5. All tab icons & labels drawn in neutral ink (tinted active blue inside GPU shader)
            int inkColor = dark ? 0xFFFFFFFF : 0xFF111111;
            drawAllTabItems(canvas, inkColor, false);
        }

        private void drawAllTabItems(Canvas canvas, int color, boolean activeBold)
        {
            int count = owner.tabs.size();
            if (count == 0)
                return;
            float tabW = capsuleRect.width() / count;
            // V6: foreground content grows from 1.0 to 1.20 only while pressed;
            // geometry of the dock itself is independent (no font shrinking).
            float contentScale = 1f + 0.20f * clamp(inflateProgress, 0f, 1f);
            for (int i = 0; i < count; i++)
            {
                TabItemInfo info = owner.tabs.get(i);
                float cx = capsuleRect.left + tabW * (i + 0.5f);
                float cy = capsuleRect.centerY();
                boolean underLens = i == activeIndex || (pointerDown && i == hoveredIndex);
                int save = canvas.save();
                if (underLens && contentScale > 1.001f)
                {
                    canvas.translate(cx, cy);
                    canvas.scale(contentScale, contentScale);
                    canvas.translate(-cx, -cy);
                }
                drawSingleTabItem(canvas, info, cx, cy, color, activeBold || i == activeIndex);
                canvas.restoreToCount(save);
            }
        }

        private void drawSingleTabItem(Canvas canvas, TabItemInfo info, float cx, float cy, int color, boolean bold)
        {
            float iconSize = dpf(22f - 1.5f * collapseProgress);
            // Smoothly glide icon from upper position down to exact vertical center as labels fade
            float iconCy = cy - dpf(7.5f) * (1f - collapseProgress);
            drawVectorTabIcon(canvas, info.kind, cx, iconCy, iconSize, color);

            float labelAlpha = clamp(1f - collapseProgress * 1.65f, 0f, 1f);
            if (labelAlpha > 0.02f)
            {
                int aColor = Color.argb((int) (Color.alpha(color) * labelAlpha),
                    Color.red(color), Color.green(color), Color.blue(color));
                textPaint.setColor(aColor);
                textPaint.setFakeBoldText(bold);
                float textY = cy + dpf(16.5f) + dpf(4f) * collapseProgress;
                canvas.drawText(info.label, cx, textY, textPaint);
            }

            if (info.badgeText != null && !info.badgeText.isEmpty())
            {
                float bx = cx + dpf(12f);
                float by = iconCy - dpf(8f);
                float bw = Math.max(dpf(15f), badgeTextPaint.measureText(info.badgeText) + dpf(7f));
                float bh = dpf(14f);
                RectF br = new RectF(bx - bw * 0.5f, by - bh * 0.5f, bx + bw * 0.5f, by + bh * 0.5f);
                canvas.drawRoundRect(br, bh * 0.5f, bh * 0.5f, badgePaint);
                canvas.drawText(info.badgeText, bx, by + dpf(3.2f), badgeTextPaint);
            }
        }

        private void drawVectorTabIcon(Canvas canvas, TabKind kind, float cx, float cy, float size, int color)
        {
            float r = size * 0.5f;
            iconPaint.setColor(color);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);

            switch (kind)
            {
                case MESSAGES:
                    iconPaint.setStyle(Paint.Style.FILL);
                    RectF bubble = new RectF(cx - r * 0.92f, cy - r * 0.80f, cx + r * 0.92f, cy + r * 0.68f);
                    canvas.drawRoundRect(bubble, r * 0.65f, r * 0.65f, iconPaint);
                    iconPath.reset();
                    iconPath.moveTo(cx - r * 0.42f, cy + r * 0.56f);
                    iconPath.lineTo(cx - r * 0.68f, cy + r * 0.92f);
                    iconPath.lineTo(cx - r * 0.08f, cy + r * 0.66f);
                    iconPath.close();
                    canvas.drawPath(iconPath, iconPaint);
                    iconPaint.setColor(owner.isDarkTheme ? 0xFF282A2E : Color.WHITE);
                    iconPaint.setStyle(Paint.Style.STROKE);
                    iconPaint.setStrokeWidth(dpf(1.8f));
                    canvas.drawLine(cx - r * 0.38f, cy - r * 0.16f, cx + r * 0.38f, cy - r * 0.16f, iconPaint);
                    canvas.drawLine(cx - r * 0.38f, cy + r * 0.14f, cx + r * 0.14f, cy + r * 0.14f, iconPaint);
                    break;

                case CONTACTS:
                    iconPaint.setStyle(Paint.Style.FILL);
                    RectF book = new RectF(cx - r * 0.78f, cy - r * 0.86f, cx + r * 0.78f, cy + r * 0.86f);
                    canvas.drawRoundRect(book, r * 0.25f, r * 0.25f, iconPaint);
                    iconPaint.setColor(owner.isDarkTheme ? 0xFF282A2E : Color.WHITE);
                    iconPaint.setStyle(Paint.Style.STROKE);
                    iconPaint.setStrokeWidth(dpf(1.7f));
                    canvas.drawCircle(cx, cy - r * 0.18f, r * 0.24f, iconPaint);
                    canvas.drawArc(cx - r * 0.42f, cy + r * 0.16f, cx + r * 0.42f, cy + r * 0.85f, 195f, 150f, false, iconPaint);
                    break;

                case DISCOVERY:
                    iconPaint.setStyle(Paint.Style.FILL);
                    float gap = r * 0.14f;
                    float sq = r * 0.66f;
                    float cr = r * 0.14f;
                    canvas.drawRoundRect(cx - sq - gap, cy - sq - gap, cx - gap, cy - gap, cr, cr, iconPaint);
                    canvas.drawRoundRect(cx - sq - gap, cy + gap, cx - gap, cy + sq + gap, cr, cr, iconPaint);
                    canvas.drawRoundRect(cx + gap, cy + gap, cx + sq + gap, cy + sq + gap, cr, cr, iconPaint);
                    canvas.save();
                    canvas.rotate(45f, cx + gap + sq * 0.5f, cy - gap - sq * 0.5f);
                    canvas.drawRoundRect(cx + gap + r * 0.06f, cy - sq - gap + r * 0.06f, cx + sq + gap - r * 0.06f, cy - gap - r * 0.06f, cr * 0.8f, cr * 0.8f, iconPaint);
                    canvas.restore();
                    break;

                case TIMELINE:
                    iconPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, r * 0.84f, iconPaint);
                    iconPaint.setColor(owner.isDarkTheme ? 0xFF282A2E : Color.WHITE);
                    iconPaint.setStyle(Paint.Style.STROKE);
                    iconPaint.setStrokeWidth(dpf(1.9f));
                    canvas.drawLine(cx, cy - r * 0.44f, cx, cy + r * 0.04f, iconPaint);
                    canvas.drawLine(cx, cy + r * 0.04f, cx + r * 0.36f, cy + r * 0.24f, iconPaint);
                    break;

                case PROFILE:
                    iconPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy - r * 0.36f, r * 0.38f, iconPaint);
                    iconPath.reset();
                    iconPath.moveTo(cx - r * 0.78f, cy + r * 0.84f);
                    iconPath.quadTo(cx - r * 0.78f, cy + r * 0.20f, cx, cy + r * 0.20f);
                    iconPath.quadTo(cx + r * 0.78f, cy + r * 0.20f, cx + r * 0.78f, cy + r * 0.84f);
                    iconPath.close();
                    canvas.drawPath(iconPath, iconPaint);
                    break;

                case GROUPS:
                    iconPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx - r * 0.28f, cy - r * 0.30f, r * 0.30f, iconPaint);
                    canvas.drawCircle(cx + r * 0.34f, cy - r * 0.22f, r * 0.24f, iconPaint);
                    canvas.drawArc(cx - r * 0.88f, cy + r * 0.12f, cx + r * 0.32f, cy + r * 1.10f, 180f, 180f, true, iconPaint);
                    canvas.drawArc(cx - r * 0.10f, cy + r * 0.20f, cx + r * 0.88f, cy + r * 1.05f, 180f, 180f, true, iconPaint);
                    break;

                case MORE:
                default:
                    iconPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx - r * 0.56f, cy, r * 0.18f, iconPaint);
                    canvas.drawCircle(cx, cy, r * 0.18f, iconPaint);
                    canvas.drawCircle(cx + r * 0.56f, cy, r * 0.18f, iconPaint);
                    break;
            }
        }

        @Override
        protected void onDraw(Canvas canvas)
        {
            super.onDraw(canvas);
            if (isCapturingBackdrop || baseCapsuleRect.isEmpty() || owner.tabs.isEmpty())
                return;

            updateDynamicCapsuleRect();
            boolean dark = owner.isDarkTheme;
            computeDropletRect(dropletRect);
            float dropRadius = dropletRect.height() * 0.5f;
            float capRadius = capsuleRect.height() * 0.5f;

            if (Build.VERSION.SDK_INT >= 33
                && canvas.isHardwareAccelerated()
                && rawBackdropNodeObj instanceof RenderNode rawNode
                && frostedBackdropNodeObj instanceof RenderNode frostNode
                && unifiedRenderNodeObj instanceof RenderNode node
                && runtimeShaderObj instanceof RuntimeShader shader
                && !dropletRect.isEmpty())
            {
                int w = getWidth();
                int h = getHeight();

                // 1 + 2. Capture live Zalo views & wallpaper underneath.
                // Reuse the recording between frames - re-rendering the whole
                // content view on every animation frame is what caused the lag.
                long now = SystemClock.uptimeMillis();
                if (!backdropRecorded || now - lastBackdropCaptureMs >= 120L
                    || rawNode.getWidth() != w || rawNode.getHeight() != h)
                {
                    rawNode.setPosition(0, 0, w, h);
                    RecordingCanvas rawRc = rawNode.beginRecording();
                    captureLiveBackdrop(rawRc);
                    rawNode.endRecording();

                    frostNode.setPosition(0, 0, w, h);
                    RecordingCanvas frostRc = frostNode.beginRecording();
                    frostRc.drawRenderNode(rawNode);
                    frostNode.endRecording();
                    frostNode.setRenderEffect(RenderEffect.createBlurEffect(dpf(8f), dpf(8f), Shader.TileMode.CLAMP));
                    lastBackdropCaptureMs = now;
                    backdropRecorded = true;
                }

                // 3. Combine sharp backdrop (for protruding lens) + frosted capsule + glass foreground
                node.setPosition(0, 0, w, h);
                RecordingCanvas rc = node.beginRecording();
                rc.drawRenderNode(rawNode);
                rc.save();
                rc.clipPath(capsuleClipPath);
                rc.drawRenderNode(frostNode);
                rc.restore();
                drawGlassForeground(rc, dark, true);
                node.endRecording();

                shader.setFloatUniform("uBarRect", capsuleRect.left, capsuleRect.top, capsuleRect.right, capsuleRect.bottom);
                shader.setFloatUniform("uBarRadius", capRadius);
                shader.setFloatUniform("uRect", dropletRect.left, dropletRect.top, dropletRect.right, dropletRect.bottom);
                shader.setFloatUniform("uRadius", dropRadius);
                shader.setFloatUniform("uInflate", inflateProgress);
                shader.setFloatUniform("uDensity", density);
                shader.setFloatUniform("uDark", dark ? 1f : 0f);
                int liquidAccent = liquidAccent(dark);
                shader.setFloatUniform("uAccent",
                    Color.red(liquidAccent) / 255f,
                    Color.green(liquidAccent) / 255f,
                    Color.blue(liquidAccent) / 255f);
                node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));

                canvas.drawRenderNode(node);
            }
            else
            {
                drawGlassForeground(canvas, dark, false);
                if (!dropletRect.isEmpty())
                {
                    int activeBlue = liquidAccent(dark);
                    canvas.save();
                    clipPath.reset();
                    clipPath.addRoundRect(dropletRect, dropRadius, dropRadius, Path.Direction.CW);
                    canvas.clipPath(clipPath);
                    drawAllTabItems(canvas, activeBlue, true);
                    canvas.restore();

                    if (inflateProgress > 0.02f)
                    {
                        fallbackRimPaint.setStrokeWidth(dpf(1.0f));
                        fallbackRimPaint.setColor(Color.argb((int) (220 * inflateProgress), 255, 255, 255));
                        canvas.drawRoundRect(dropletRect, dropRadius, dropRadius, fallbackRimPaint);
                    }
                }
            }
        }

        private void computeDropletRect(RectF out)
        {
            int count = owner.tabs.size();
            if (capsuleRect.isEmpty() || count == 0)
            {
                out.setEmpty();
                return;
            }
            float tabW = capsuleRect.width() / count;
            float restH = Math.max(capsuleRect.height() - dpf(10f), dpf(36f));
            float restW = Math.max(tabW - dpf(4f), restH * 1.22f);

            // V6: pressed droplet scale 1.42 (clamped 1.52), velocity stretch
            // up to +24% horizontally and -14% vertically, spring-driven.
            float w = restW * barScale * (1f + stretchX * 0.24f);
            float h = restH * scaleY * (1f - stretchY * 0.14f);

            float cx = capsuleRect.left + currentTabPos * tabW;
            float cy = capsuleRect.centerY();
            // Enlarge the accent-colored overlay by another 1px (2px total)
            // so the pale layer behind cannot leak outside the droplet.
            out.set(cx - w * 0.5f - 2f, cy - h * 0.5f - 2f,
                cx + w * 0.5f + 2f, cy + h * 0.5f + 2f);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event)
        {
            int count = owner.tabs.size();
            if (count == 0 || capsuleRect.isEmpty())
                return false;

            float x = event.getX();
            float y = event.getY();
            int action = event.getActionMasked();

            switch (action)
            {
                case MotionEvent.ACTION_DOWN:
                    if (x < capsuleRect.left - dpf(8f) || x > capsuleRect.right + dpf(8f)
                        || y < capsuleRect.top - dpf(10f) || y > capsuleRect.bottom + dpf(10f))
                        return false;

                    pointerDown = true;
                    isDragging = false;
                    downX = x;
                    lastTouchX = x;
                    lastTouchMs = SystemClock.uptimeMillis();
                    if (getParent() != null)
                        getParent().requestDisallowInterceptTouchEvent(true);

                    if (targetCollapse > 0.5f)
                        targetCollapse = 0f;

                    touchGlowX = x;
                    touchGlowY = y;
                    targetTouchGlow = 1f;
                    targetBarOffsetX = clamp((x - baseCapsuleRect.centerX()) * 0.022f, -dpf(4.5f), dpf(4.5f));
                    targetBarOffsetY = clamp((y - baseCapsuleRect.centerY()) * 0.03f, -dpf(2.2f), dpf(2.2f));
                    targetBarScale = 1.42f;

                    int hit = indexForX(x, count);
                    hoveredIndex = hit;
                    targetInflate = 1.0f;
                    updateTargetForIndex(hit);
                    startAnimationLoop();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!pointerDown)
                        return false;
                    if (!isDragging && Math.abs(x - downX) > touchSlop * 0.4f)
                        isDragging = true;

                    long now = SystemClock.uptimeMillis();
                    float dt = Math.max(1f, now - lastTouchMs);
                    float instVel = (x - lastTouchX) / dt * 16f;
                    lastTouchX = x;
                    lastTouchMs = now;

                    touchGlowX = clamp(x, capsuleRect.left, capsuleRect.right);
                    touchGlowY = clamp(y, capsuleRect.top, capsuleRect.bottom);
                    targetTouchGlow = 1f;
                    targetBarOffsetX = clamp((x - baseCapsuleRect.centerX()) * 0.026f, -dpf(5.5f), dpf(5.5f));
                    targetBarOffsetY = clamp((y - baseCapsuleRect.centerY()) * 0.035f, -dpf(2.5f), dpf(2.5f));
                    targetBarScale = 1.42f;

                    float tabW = capsuleRect.width() / count;
                    float minCx = capsuleRect.left + tabW * 0.44f;
                    float maxCx = capsuleRect.right - tabW * 0.44f;
                    float clampedX = clamp(x, minCx, maxCx);

                    targetTabPos = (clampedX - capsuleRect.left) / Math.max(tabW, 1f);
                    targetInflate = 1.0f;
                    stretchX = clamp(instVel * 0.018f, -0.22f, 0.22f);

                    int newHover = indexForX(clampedX, count);
                    if (newHover != hoveredIndex)
                    {
                        hoveredIndex = newHover;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    startAnimationLoop();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!pointerDown)
                        return false;
                    pointerDown = false;
                    boolean didDrag = isDragging;
                    isDragging = false;
                    targetInflate = 0f;
                    targetTouchGlow = 0f;
                    targetBarOffsetX = 0f;
                    targetBarOffsetY = 0f;
                    targetBarScale = 1f;

                    int finalIdx = didDrag ? clampInt(hoveredIndex, 0, count - 1) : indexForX(x, count);
                    activeIndex = finalIdx;
                    hoveredIndex = finalIdx;
                    updateTargetForIndex(finalIdx);
                    startAnimationLoop();

                    if (action == MotionEvent.ACTION_UP)
                        owner.selectTab(finalIdx);
                    return true;
            }
            return super.onTouchEvent(event);
        }

        private int indexForX(float x, int count)
        {
            float rel = (x - capsuleRect.left) / Math.max(capsuleRect.width(), 1f);
            return clampInt((int) Math.floor(rel * count), 0, count - 1);
        }

        private void updateTargetForIndex(int idx)
        {
            int count = owner.tabs.size();
            if (count == 0)
                return;
            targetTabPos = idx + 0.5f;
        }

        private void startAnimationLoop()
        {
            if (animating)
                return;
            animating = true;
            lastFrameMs = SystemClock.uptimeMillis();
            postOnAnimation(animTick);
        }

        private int liquidAccent(boolean dark)
        {
            // Always share the Zalo accent (Monet from the Android system
            // palette on 12+, wallpaper image only on older Android).
            return ThemeColors.getAccent(getContext());
        }

        private final Runnable animTick = new Runnable()
        {
            @Override
            public void run()
            {
                long now = SystemClock.uptimeMillis();
                float dt = clamp((now - lastFrameMs) / 1000f, 0.001f, 0.034f);
                lastFrameMs = now;

                // Honor system-wide animation scale / reduced motion.
                if (!android.animation.ValueAnimator.areAnimatorsEnabled())
                {
                    posSpring.snapTo(targetTabPos);
                    pressSpring.snapTo(targetInflate);
                    scaleXSpring.snapTo(targetBarScale);
                    scaleYSpring.snapTo(targetBarScale);
                    stretchSpring.snapTo(0f);
                    currentTabPos = targetTabPos;
                    velocityTab = 0f;
                    inflateProgress = targetInflate;
                    barScale = scaleY = targetBarScale;
                    stretchX = stretchY = 0f;
                    touchGlowAlpha = targetTouchGlow;
                    barOffsetX = targetBarOffsetX;
                    barOffsetY = targetBarOffsetY;
                    collapseProgress = targetCollapse;
                    animating = false;
                    invalidate();
                    return;
                }

                posSpring.animateTo(targetTabPos);
                boolean moving = posSpring.update(dt);
                currentTabPos = posSpring.value();
                velocityTab = posSpring.velocity();

                pressSpring.animateTo(targetInflate);
                moving |= pressSpring.update(dt);
                inflateProgress = clamp(pressSpring.value(), 0f, 1f);

                // Droplet expands to 1.42 while held/dragged, clamped to 1.52.
                scaleXSpring.animateTo(targetBarScale);
                moving |= scaleXSpring.update(dt);
                barScale = clamp(scaleXSpring.value(), 1f, 1.52f);
                scaleYSpring.animateTo(targetBarScale);
                moving |= scaleYSpring.update(dt);
                scaleY = clamp(scaleYSpring.value(), 1f, 1.52f);

                // Normalized velocity -> organic stretch (max +24% X / -14% Y).
                float normalizedVelocity = clamp(velocityTab / 4f, -1f, 1f);
                float stretchTarget = (pointerDown || isDragging) ? Math.abs(normalizedVelocity) : 0f;
                stretchSpring.animateTo(stretchTarget);
                moving |= stretchSpring.update(dt);
                stretchX = clamp(stretchSpring.value(), 0f, 1f);
                stretchY = stretchX;

                float lerp = 1f - (float) Math.exp(-18f * dt);
                touchGlowAlpha += (targetTouchGlow - touchGlowAlpha) * (1f - (float) Math.exp(-14f * dt));
                barOffsetX += (targetBarOffsetX - barOffsetX) * lerp;
                barOffsetY += (targetBarOffsetY - barOffsetY) * lerp;
                collapseProgress += (targetCollapse - collapseProgress) * (1f - (float) Math.exp(-12f * dt));

                invalidate();

                boolean done = Math.abs(targetTabPos - currentTabPos) < 0.004f
                    && Math.abs(velocityTab) < 0.02f
                    && Math.abs(targetInflate - inflateProgress) < 0.004f
                    && Math.abs(targetTouchGlow - touchGlowAlpha) < 0.005f
                    && Math.abs(targetBarOffsetX - barOffsetX) < 0.15f
                    && Math.abs(targetBarOffsetY - barOffsetY) < 0.15f
                    && Math.abs(targetBarScale - barScale) < 0.001f
                    && Math.abs(targetBarScale - scaleY) < 0.001f
                    && Math.abs(targetCollapse - collapseProgress) < 0.005f
                    && Math.abs(stretchSpring.value() - stretchSpring.targetOf()) < 0.004f
                    && !pointerDown;

                if (done && !moving)
                {
                    posSpring.snapTo(targetTabPos);
                    pressSpring.snapTo(targetInflate);
                    scaleXSpring.snapTo(targetBarScale);
                    scaleYSpring.snapTo(targetBarScale);
                    stretchSpring.snapTo(0f);
                    currentTabPos = targetTabPos;
                    velocityTab = 0f;
                    inflateProgress = targetInflate;
                    barScale = scaleY = targetBarScale;
                    stretchX = stretchY = 0f;
                    touchGlowAlpha = targetTouchGlow;
                    barOffsetX = targetBarOffsetX;
                    barOffsetY = targetBarOffsetY;
                    collapseProgress = targetCollapse;
                    animating = false;
                    invalidate();
                }
                else
                {
                    postOnAnimation(this);
                }
            }
        };

        private int dp(float v)
        {
            return Math.round(v * density);
        }

        private float dpf(float v)
        {
            return v * density;
        }

        private static float clamp(float v, float min, float max)
        {
            return Math.max(min, Math.min(max, v));
        }

        private static int clampInt(int v, int min, int max)
        {
            return Math.max(min, Math.min(max, v));
        }
    }
}
