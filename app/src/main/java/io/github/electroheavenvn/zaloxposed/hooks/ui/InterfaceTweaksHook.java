package io.github.electroheavenvn.zaloxposed.hooks.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Visual interface tweaks: avatar ring, hidden-message icon animation,
 * upload quality override and a compact message-list top bar.
 *
 * Every hook is guarded by its own configuration switch and fails open.
 */
public class InterfaceTweaksHook extends BaseHook
{
    private static final ThreadLocal<Boolean> HIDDEN_ICON_SIGNAL =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final Paint RING_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static SweepGradient ringShader;

    @Override
    public void hook() throws Throwable
    {
        if (!Config.getEnableChatAvatarComet() && !Config.getEnableHiddenSearchSparkles()
            && !Config.getEnableMaxVideoQuality() && !Config.getEnableMinimalTopBar())
            return;
        hookAvatarRing();
        hookHiddenSearchIcon();
        hookVideoQuality();
        hookMinimalTopBar();
        Logger.i("Interface tweaks installed");
    }

    // ------------------------------------------------------------------ lookup

    private List<Method> findMethods(Class<?> clazz, String name, String returnType, int paramCount,
                                     String... paramTypes)
    {
        List<Method> result = new ArrayList<>();
        if (clazz == null)
            return result;
        try
        {
            MethodMatcher matcher = MethodMatcher.create()
                .declaredClass(clazz)
                .name(name)
                .returnType(returnType)
                .paramCount(paramCount);
            if (paramTypes != null && paramTypes.length > 0)
                matcher.paramTypes(paramTypes);
            List<MethodData> data = bridge.findMethod(FindMethod.create().matcher(matcher));
            for (MethodData methodData : data)
            {
                try { result.add(methodData.getMethodInstance(classLoader)); }
                catch (Throwable ignored) { }
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
        return result;
    }

    private static Field findField(Class<?> clazz, String name)
    {
        for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass())
        {
            try { return current.getDeclaredField(name); }
            catch (Throwable ignored) { }
        }
        return null;
    }

    // --------------------------------------------------------- avatar ring

    private void hookAvatarRing()
    {
        Class<?> chatRow;
        try { chatRow = Class.forName("com.zing.zalo.ui.chat.chatrow.ChatRow", false, classLoader); }
        catch (Throwable t) { Logger.e(t); return; }

        List<Method> onDraws = findMethods(chatRow, "onDraw", "void", 1, "android.graphics.Canvas");
        for (Method onDraw : onDraws)
        {
            doHook(onDraw, chain ->
            {
                Object result = chain.proceed();
                if (!Config.getEnableChatAvatarComet())
                    return result;
                Object canvasArg = chain.getArg(0);
                Object self = chain.getThisObject();
                if (!(canvasArg instanceof Canvas canvas) || !(self instanceof View view) || !view.isShown())
                    return result;
                AvatarRing.draw(view, canvas);
                if (view.isAttachedToWindow())
                    view.postInvalidateOnAnimation();
                return result;
            });
        }
    }

    private static final class AvatarRing
    {
        private AvatarRing() { }

        static void draw(View view, Canvas canvas)
        {
            float density = view.getResources().getDisplayMetrics().density;
            float center = Math.round(28f * density) / 2f;
            float radius = center - density;
            if (radius <= 0f)
                return;

            if (ringShader == null)
            {
                int trail = 0xFF6BA8FF;
                ringShader = new SweepGradient(0f, 0f,
                    new int[] { 0, 0, trail & 0x26FFFFFF, trail, 0xFFF2F7FF, 0 },
                    new float[] { 0f, .64f, .71f, .92f, .975f, 1f });
            }
            boolean animate = Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled();
            float angle = animate ? (SystemClock.uptimeMillis() % 3200L) * (360f / 3200f) : 0f;

            int checkpoint = canvas.save();
            canvas.translate(center, center);
            canvas.rotate(angle);
            RING_PAINT.setStyle(Paint.Style.STROKE);
            RING_PAINT.setStrokeWidth(1.5f * density);
            RING_PAINT.setShader(ringShader);
            canvas.drawCircle(0f, 0f, radius, RING_PAINT);
            canvas.restoreToCount(checkpoint);
        }
    }

    // ------------------------------------------------------- hidden icon

    private void hookHiddenSearchIcon()
    {
        hookIconSignal("com.zing.zalo.ui.maintab.MainTabView", "u6");
        hookIconSignal("com.zing.zalo.ui.maintab.msg.MessagesView", "f7");

        try
        {
            List<MethodData> clicks = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .name("onClick")
                    .returnType("void")
                    .paramCount(1)
                    .paramTypes("android.view.View")
                    .addUsingString("HAS_MSG_HIDDEN_CHAT_NEW", StringMatchType.Equals)));
            for (MethodData data : clicks)
            {
                try
                {
                    Method method = data.getMethodInstance(classLoader);
                    doHook(method, chain ->
                    {
                        HIDDEN_ICON_SIGNAL.set(Boolean.TRUE);
                        try { return chain.proceed(); }
                        finally { HIDDEN_ICON_SIGNAL.set(Boolean.FALSE); }
                    });
                }
                catch (Throwable ignored) { }
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }

        try
        {
            Method setColorFilter = ImageView.class.getDeclaredMethod("setColorFilter", int.class);
            doHook(setColorFilter, chain ->
            {
                Object result = chain.proceed();
                if (Config.getEnableHiddenSearchSparkles()
                    && Boolean.TRUE.equals(HIDDEN_ICON_SIGNAL.get())
                    && chain.getThisObject() instanceof ImageView view)
                    SparkleIcon.apply(view);
                return result;
            });
            Method clearColorFilter = ImageView.class.getDeclaredMethod("clearColorFilter");
            doHook(clearColorFilter, chain ->
            {
                Object result = chain.proceed();
                if (Config.getEnableHiddenSearchSparkles()
                    && Boolean.TRUE.equals(HIDDEN_ICON_SIGNAL.get())
                    && chain.getThisObject() instanceof ImageView view)
                    SparkleIcon.restore(view);
                return result;
            });
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private void hookIconSignal(String className, String methodName)
    {
        try
        {
            Class<?> clazz = Class.forName(className, false, classLoader);
            for (Method method : findMethods(clazz, methodName, "void", 1))
            {
                doHook(method, chain ->
                {
                    HIDDEN_ICON_SIGNAL.set(Boolean.TRUE);
                    try { return chain.proceed(); }
                    finally { HIDDEN_ICON_SIGNAL.set(Boolean.FALSE); }
                });
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private static final class SparkleIcon
    {
        private SparkleIcon() { }

        static void apply(ImageView view)
        {
            if (view.getDrawable() instanceof SparkleDrawable)
                return;
            try
            {
                Drawable original = view.getDrawable();
                view.clearAnimation();
                view.animate().cancel();
                view.setScaleX(1f);
                view.setScaleY(1f);
                view.clearColorFilter();
                float density = view.getResources().getDisplayMetrics().density;
                view.setImageDrawable(new SparkleDrawable(original, Math.round(28f * density)));
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }

        static void restore(ImageView view)
        {
            try
            {
                Drawable current = view.getDrawable();
                if (current instanceof SparkleDrawable sparkle)
                {
                    sparkle.unscheduleSelf(sparkle.redraw);
                    view.setImageDrawable(sparkle.original);
                }
                view.clearColorFilter();
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }

        private static final class SparkleDrawable extends Drawable
        {
            final Drawable original;
            final int size;
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Path star = new Path();
            final Runnable redraw = this::invalidateSelf;
            private int alpha = 255;

            SparkleDrawable(Drawable original, int size)
            {
                this.original = original;
                this.size = size;
            }

            @Override public int getIntrinsicWidth() { return size; }
            @Override public int getIntrinsicHeight() { return size; }

            @Override
            public void setAlpha(int value)
            {
                alpha = value;
                invalidateSelf();
            }

            @Override
            public void setColorFilter(ColorFilter filter) { }

            @Override
            public int getOpacity() { return PixelFormat.TRANSLUCENT; }

            @Override
            public void draw(Canvas canvas)
            {
                int frame = canvas.save();
                canvas.translate(getBounds().left, getBounds().top);
                canvas.scale(getBounds().width() / 28f, getBounds().height() / 28f);
                paint.setColor(Color.WHITE);
                paint.setAlpha(alpha);
                paint.setStrokeWidth(2.4f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(11.5f, 12.5f, 5.5f, paint);
                canvas.drawLine(15.6f, 16.6f, 21f, 22f, paint);
                paint.setStyle(Paint.Style.FILL);
                long now = SystemClock.uptimeMillis();
                drawStar(canvas, 19.5f, 5.5f, 2.6f, now, 0);
                drawStar(canvas, 24f, 11f, 2.3f, now, 320);
                drawStar(canvas, 5f, 5f, 2.2f, now, 670);
                canvas.restoreToCount(frame);
                if (isVisible())
                {
                    unscheduleSelf(redraw);
                    scheduleSelf(redraw, now + 50);
                }
            }

            private void drawStar(Canvas canvas, float x, float y, float radius, long now, int offset)
            {
                float pulse = (1f + (float)Math.sin((now + offset) % 1100L * 2f * Math.PI / 1100f)) / 2f;
                float r = radius * (0.78f + 0.22f * pulse);
                float waist = r * 0.27f;
                paint.setAlpha(Math.round(alpha * (0.48f + 0.52f * pulse)));
                star.reset();
                star.moveTo(x, y - r);
                star.lineTo(x + waist, y - waist);
                star.lineTo(x + r, y);
                star.lineTo(x + waist, y + waist);
                star.lineTo(x, y + r);
                star.lineTo(x - waist, y + waist);
                star.lineTo(x - r, y);
                star.lineTo(x - waist, y - waist);
                star.close();
                canvas.drawPath(star, paint);
            }
        }
    }

    // ------------------------------------------------------- video quality

    private void hookVideoQuality()
    {
        Class<?> config;
        try { config = Class.forName("com.zing.zalo.config.VideoNativeCompressConfig", false, classLoader); }
        catch (Throwable t) { Logger.e(t); return; }

        for (Method method : findMethods(config, "c", "float", 0))
        {
            doHook(method, chain ->
                Config.getEnableMaxVideoQuality() ? 1080f : chain.proceed());
        }
        for (Method method : findMethods(config, "f", "void", 1, "org.json.JSONObject"))
        {
            doHook(method, chain ->
            {
                Object result = chain.proceed();
                if (!Config.getEnableMaxVideoQuality())
                    return result;
                Object self = chain.getThisObject();
                setStringField(self, "a", "1080p");
                setStringField(self, "b", "8000000");
                setStringField(self, "c", "8000000");
                setStringField(self, "d", "60");
                return result;
            });
        }
    }

    private static void setStringField(Object target, String name, String value)
    {
        if (target == null)
            return;
        try
        {
            Field field = findField(target.getClass(), name);
            if (field != null && String.class.isAssignableFrom(field.getType()))
            {
                field.setAccessible(true);
                field.set(target, value);
            }
        }
        catch (Throwable ignored) { }
    }

    // --------------------------------------------------------- minimal bar

    private void hookMinimalTopBar()
    {
        Class<?> mainTab;
        try { mainTab = Class.forName("com.zing.zalo.ui.maintab.MainTabView", false, classLoader); }
        catch (Throwable t) { Logger.e(t); return; }

        List<Method> menuMethods = findMethods(mainTab, "m5", "void", 1);
        for (Method method : menuMethods)
        {
            doHook(method, chain ->
            {
                Object result = chain.proceed();
                if (!Config.getEnableMinimalTopBar())
                    return result;
                Object self = chain.getThisObject();
                applyTopBarTweaks(self);
                if (self instanceof View view)
                    observeTopBar(view, self);
                return result;
            });
        }

        // Fallback for builds where the menu method is named differently.
        for (Method method : findMethods(mainTab, "onCreateView", "android.view.View", 3))
        {
            doHook(method, chain ->
            {
                Object result = chain.proceed();
                if (!Config.getEnableMinimalTopBar() || !(result instanceof View root))
                    return result;
                observeTopBar(root, root);
                return result;
            });
        }
    }

    private static void observeTopBar(View view, Object target)
    {
        if ("ui_tweak_topbar_watched".equals(view.getTag()))
        {
            applyTopBarTweaks(target);
            return;
        }
        view.setTag("ui_tweak_topbar_watched");
        applyTopBarTweaks(target);
        try
        {
            view.post(() -> applyTopBarTweaks(target));
            view.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener()
            {
                @Override
                public void onGlobalLayout()
                {
                    applyTopBarTweaks(target);
                }
            });
        }
        catch (Throwable ignored) { }
    }

    private static void applyTopBarTweaks(Object target)
    {
        if (target == null)
            return;
        try
        {
            hideFieldView(target, "D1");
            hideFieldView(target, "q2");
            hideFieldView(target, "n2");
        }
        catch (Throwable ignored) { }
        if (target instanceof View root)
            hideTopControls(root, root, 0);
    }

    private static void hideFieldView(Object target, String fieldName)
    {
        try
        {
            Field field = findField(target.getClass(), fieldName);
            if (field != null)
            {
                field.setAccessible(true);
                Object value = field.get(target);
                if (value instanceof View view)
                    hideView(view);
            }
        }
        catch (Throwable ignored) { }
    }

    private static void hideView(View view)
    {
        if (view.getVisibility() != View.GONE)
        {
            view.setVisibility(View.GONE);
            view.setClickable(false);
        }
    }

    private static void hideTopControls(View view, View root, int depth)
    {
        if (view == null || depth > 10)
            return;
        if (view != root && looksLikeTopControl(view))
            hideView(view);
        if (view instanceof ViewGroup group)
        {
            for (int i = 0; i < group.getChildCount(); i++)
                hideTopControls(group.getChildAt(i), root, depth + 1);
        }
    }

    private static boolean looksLikeTopControl(View view)
    {
        try
        {
            int id = view.getId();
            if (id != View.NO_ID)
            {
                String name = view.getResources().getResourceEntryName(id).toLowerCase();
                if (name.contains("search") || name.contains("qrcode")
                    || name.contains("qr_code") || name.contains("plus")
                    || name.contains("action_bar_add"))
                    return true;
            }
            String simple = view.getClass().getSimpleName().toLowerCase();
            return simple.contains("search") || simple.contains("qrcode");
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }
}
