package com.huaxidesu.gtnchat.client;

import com.huaxidesu.gtnchat.chat.ChatHeads;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 探测本客户端会不会在聊天里画玩家头像（NecroTempus 的 ChatHeads）。
 *
 * <p>服务端没法知道对面装了什么客户端模组，只能由这里读了配置再在回执里上报。
 * 用反射：本模组不依赖 NecroTempus，没装时整个探测返回 false。
 *
 * <p>读的是静态字段 {@code NecroTempusConfig.ChatHeadsEnabled}，也是它自己渲染时判断的那个值，
 * 所以结论和实际画不画是一致的。
 */
@SideOnly(Side.CLIENT)
public final class ChatHeadsProbe {

    private static Boolean cached;

    private ChatHeadsProbe() {}

    /** 回执里的能力位 */
    public static byte caps() {
        return heads() ? ChatHeads.CAP_CHAT_HEADS : 0;
    }

    public static boolean heads() {
        Boolean value = cached;
        if (value == null) {
            value = Boolean.valueOf(probe());
            cached = value;
        }
        return value.booleanValue();
    }

    private static boolean probe() {
        try {
            Class<?> config = Class.forName("io.github.cruciblemc.necrotempus.NecroTempusConfig");
            return config.getField("ChatHeadsEnabled").getBoolean(null);
        } catch (Throwable ignored) {
            // 没装 NecroTempus（大多数客户端），或者它的字段改了名
            return false;
        }
    }
}
