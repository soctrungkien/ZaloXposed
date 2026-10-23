package io.github.electroheavenvn.zaloxposed.hooks.functional;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Config;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;
import io.github.electroheavenvn.zaloxposed.utilities.Utils;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Bypasses the subscription entitlement check that gates the chat reaction
 * picker. When enabled, the original boolean configuration field is returned
 * early, matching the upstream bytecode transformation.
 */
public class UnlockChatReactionHook extends BaseHook
{
    @Override
    public void hook() throws Throwable
    {
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                .returnType("boolean")
                .paramCount(0)
                .addUsingString("subscribePackageIdConfig", StringMatchType.Equals)
            ));
        if (methods.isEmpty())
        {
            Logger.e("Chat reaction entitlement method not found");
            return;
        }
        for (MethodData methodData : methods)
        {
            Method method = methodData.getMethodInstance(classLoader);
            Field booleanField = findFirstBooleanField(method);
            Logger.i("Hooking: " + method);
            module.hook(method).intercept(chain ->
            {
                if (!Config.getUnlockChatReaction())
                    return chain.proceed();
                if (booleanField != null)
                {
                    try
                    {
                        return booleanField.getBoolean(chain.getThisObject());
                    }
                    catch (Throwable ignored) { }
                }
                return true;
            });
        }
    }

    private Field findFirstBooleanField(Method method)
    {
        try
        {
            for (Instruction instruction : Utils.Disassemble(method))
            {
                if (instruction.getOpcode() != Opcode.IGET_BOOLEAN)
                    continue;
                if (!(instruction instanceof ReferenceInstruction))
                    continue;
                Object reference = ((ReferenceInstruction)instruction).getReference();
                if (!(reference instanceof FieldReference))
                    continue;
                FieldReference fieldReference = (FieldReference)reference;
                Class<?> clazz = Class.forName(
                    Utils.DescriptorToClassName(fieldReference.getDefiningClass()),
                    false,
                    classLoader);
                Field field = findField(clazz, fieldReference.getName());
                if (field != null)
                {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
        return null;
    }

    private Field findField(Class<?> clazz, String name)
    {
        Class<?> current = clazz;
        while (current != null && current != Object.class)
        {
            try
            {
                return current.getDeclaredField(name);
            }
            catch (NoSuchFieldException ignored)
            {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
