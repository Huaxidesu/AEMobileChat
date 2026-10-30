package com.huaxidesu.gtnchat.util;

import net.minecraft.util.IChatComponent;

/**
 * 少量必须用反射才能拿到的值。
 *
 * <p>成就名的取值方法在不同映射集下名字不一致，直接按名字编译会在换映射时编译不过，
 * 这里两种名字都试一次；都失败时返回空串，由调用方退回用成就 ID。
 */
public final class Reflect {

    private Reflect() {}

    public static String statName(Object stat) {
        if (stat == null) return "";
        Object name = invoke(stat, "getStatName", "func_150951_e");
        if (name instanceof IChatComponent) {
            try {
                return ((IChatComponent) name).getUnformattedText();
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static Object invoke(Object target, String... names) {
        for (String name : names) {
            try {
                return target.getClass().getMethod(name).invoke(target);
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
