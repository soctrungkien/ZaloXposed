package io.github.electroheavenvn.zaloxposed.hooks.privacy;

import android.view.View;
import android.view.ViewGroup;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.libxposed.api.XposedInterface;

/**
 * Port of the readonly screen-effect blockers.
 *
 * Message effects are blocked through the animation cache, while sticker
 * effects are blocked at the view/keyframe layer. The view layer check only
 * targets sticker-specific classes so that normal Zalo UI views are untouched.
 */
public class BlockScreenEffectHook extends BaseHook
{
    private static final Pattern P_MESSAGE = Pattern.compile(
        "^com\\.zing\\.zalo\\.(?!.*(Ripple|Blur|Shadow|Touch|Press|Transition)).*(Effect|Firework|Confetti|Rain|Snow|Particle)");
    private static final Pattern P_STICKER = Pattern.compile(
        "^com\\.zing\\.zalo\\.(?!.*(Ripple|Blur|Shadow|Touch|Press|Transition)).*(Sticker.*(Full|Effect|Overlay)|(Full|Effect|Overlay).*Sticker)");
    private static final Set<String> SEEN = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String, Integer> KINDS = new ConcurrentHashMap<>();

    @Override
    public void hook() throws Throwable
    {
        AnimationBlocker.apply(Config.getBlockMessageEffect());
        AnimationBlocker.applySticker(Config.getBlockStickerEffect());
        PunchGlassBlocker.start();

        installStickerPreview();
        installStickerFreeze();
        installLottieFullscreen();
        installKeyframeFullscreen();
        installViewLayer();
    }

    private void install(MethodMatcher matcher, XposedInterface.Hooker hooker)
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create().matcher(matcher));
        if (methods.isEmpty())
        {
            Logger.e("BlockScreenEffect: target method not found");
            return;
        }
        for (MethodData data : methods)
        {
            try
            {
                Method method = data.getMethodInstance(classLoader);
                Logger.i("Hooking: " + method);
                module.hook(method).intercept(hooker);
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }
    }

    private void installStickerPreview()
    {
        install(MethodMatcher.create()
                .returnType("void")
                .declaredClass("com.zing.zalo.ui.chat.ChatView")
                .paramCount(2)
                .addUsingString("preview_", StringMatchType.Equals),
            chain ->
            {
                if (Config.getBlockStickerEffect())
                    return null;
                return chain.proceed();
            });
    }

    private void installStickerFreeze()
    {
        install(MethodMatcher.create()
                .returnType("void")
                .paramCount(1)
                .addUsingString("/script_kf_flip.json", StringMatchType.Equals),
            chain ->
            {
                Object result = chain.proceed();
                if (Config.getBlockStickerEffect())
                {
                    try
                    {
                        freeze(chain.getThisObject());
                    }
                    catch (Throwable t)
                    {
                        Logger.e(t);
                    }
                }
                return result;
            });
    }

    private void installLottieFullscreen()
    {
        install(MethodMatcher.create()
                .returnType("void")
                .paramCount(4)
                .addUsingString("extra_effect_path", StringMatchType.Equals),
            chain ->
            {
                if (Config.getBlockStickerEffect())
                    return null;
                return chain.proceed();
            });
    }

    private void installKeyframeFullscreen()
    {
        install(MethodMatcher.create()
                .declaredClass("com.zing.zalo.common.KeyframeAnimLayout")
                .name("b")
                .returnType("void")
                .paramCount(0),
            chain ->
            {
                if (!Config.getBlockStickerEffect())
                    return chain.proceed();
                try
                {
                    Object self = chain.getThisObject();
                    Method stop = self.getClass().getDeclaredMethod("n");
                    stop.setAccessible(true);
                    stop.invoke(self);
                    return null;
                }
                catch (Throwable t)
                {
                    Logger.e(t);
                    return chain.proceed();
                }
            });
    }

    private void installViewLayer()
    {
        try
        {
            Method addView = ViewGroup.class.getDeclaredMethod(
                "addView", View.class, int.class, ViewGroup.LayoutParams.class);
            module.hook(addView).intercept(chain ->
            {
                if (shouldBlockView(chain))
                    return null;
                return chain.proceed();
            });
            Logger.i("BlockScreenEffect: view layer installed");
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private static boolean shouldBlockView(XposedInterface.Chain chain)
    {
        try
        {
            // Message effects are blocked through AnimationBlocker; the view
            // layer is only used for the more specific sticker classes.
            if (!Config.getBlockStickerEffect())
                return false;
            Object view = chain.getArg(0);
            if (view == null)
                return false;
            String name = view.getClass().getName();
            int kind = KINDS.computeIfAbsent(name, BlockScreenEffectHook::classify);
            if (kind != 2)
                return false;
            logOnce(name, kind);
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static int classify(String className)
    {
        if (P_STICKER.matcher(className).find())
            return 2;
        if (P_MESSAGE.matcher(className).find())
            return 1;
        return 0;
    }

    private static void logOnce(String className, int kind)
    {
        if (SEEN.add(className))
            Logger.i("ScreenEffect view detected: " + className + " kind=" + kind);
    }

    private static void freeze(Object host) throws Exception
    {
        Field dField = host.getClass().getDeclaredField("d");
        dField.setAccessible(true);
        Object animation = dField.get(host);
        if (animation == null)
            return;

        Field eField = animation.getClass().getDeclaredField("e");
        eField.setAccessible(true);
        Object keyframes = eField.get(animation);
        if (keyframes == null)
            return;

        Field bField = keyframes.getClass().getDeclaredField("b");
        bField.setAccessible(true);
        int frame = bField.getInt(keyframes);

        Method seek = animation.getClass().getDeclaredMethod("d", float.class);
        seek.setAccessible(true);
        seek.invoke(animation, (float)frame);

        Method invalidate = animation.getClass().getDeclaredMethod("i");
        invalidate.setAccessible(true);
        invalidate.invoke(animation);
    }
}
