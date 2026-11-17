package io.github.electroheavenvn.zaloxposed.hooks.privacy;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

public class BlockSendSeenHook extends BaseHook
{
    @Override
    public void hook() throws Throwable
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                .returnType("void")
                .paramCount(2)
                .paramTypes("java.util.ArrayList", "boolean")
                .addUsingString("SendSeenManager", StringMatchType.Equals)
                .addUsingString("MessageRepository", StringMatchType.Equals)
                .addUsingString("MsgList:", StringMatchType.Contains)
                .addUsingNumber(203)
                .addUsingNumber(107)
            ));
        if (methods.isEmpty())
            Logger.e("Target method not found");
        for (MethodData methodData : methods)
        {
            Method method = methodData.getMethodInstance(classLoader);
            Logger.i("Hooking: " + method);
            module.hook(method).intercept(chain ->
            {
                if (Config.getBlockSendSeen())
                    return null;
                return chain.proceed();
            });
        }

        // 26.10.02 anchors from the SuppressSeenStatus/HideReadReceipts patches.
        methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .addUsingString("ackMsgList", StringMatchType.Equals)
                .addUsingString("seen", StringMatchType.Equals)
                .addUsingString("data", StringMatchType.Equals)
                .addUsingNumber(10309)
                .addUsingNumber(205)
                .addUsingNumber(10305)
                .addUsingNumber(118)
            ));
        if (methods.isEmpty())
            Logger.e("Target method not found (26.10.02)");
        for (MethodData methodData : methods)
        {
            try
            {
                Method method = methodData.getMethodInstance(classLoader);
                Logger.i("Hooking: " + method);
                module.hook(method).intercept(chain ->
                {
                    if (Config.getBlockSendSeen() || Config.getHideReadReceipts())
                        return null;
                    return chain.proceed();
                });
            }
            catch (Throwable t)
            {
                Logger.e(t);
            }
        }
    }
}
