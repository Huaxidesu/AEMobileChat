package com.huaxidesu.gtnchat.bridge;

import java.lang.reflect.Method;

import net.minecraft.util.IChatComponent;

import com.huaxidesu.gtnchat.GTNHChat;
import com.huaxidesu.gtnchat.config.Config;

/**
 * 把渲染好的聊天连同元数据交给 AEMobile（手机端）。
 *
 * <p>没有这层时，AEMobile 只能在 {@code sendChatMsg} 处拿到组件：玩家名、来源服务器
 * 都已经拼进文本里了，手机端只能把它们当成「服务器广播」，也没法标注跨服来源。
 * 这里在广播前把这几项补上。
 *
 * <p><b>不做编译期依赖</b>：AEMobile 是独立模组，本类只用反射探测它的入口。
 * 探测失败（没装 AEMobile）时整体停用，聊天照常走原路径。
 *
 * <p><b>调用约定</b>：{@link #push} 之后必须紧接着用同一个组件对象调用
 * {@code ServerConfigurationManager.sendChatMsg}。AEMobile 用对象身份把这次广播认下来，
 * 不会重复入队；顺序反过来会让手机端收到两条。
 */
public final class MobileBridge {

    /** 玩家公屏（与 AEMobile 的 KIND_GAME 对齐） */
    public static final int KIND_GAME = 0;
    /** 服务器广播 */
    public static final int KIND_SERVER = 4;
    /** 成就公告 */
    public static final int KIND_ACHIEVEMENT = 6;

    private static boolean checked;
    private static boolean enabled;
    private static Method accept;

    private MobileBridge() {}

    public static boolean enabled() {
        check();
        return enabled;
    }

    private static synchronized void check() {
        if (checked) return;
        checked = true;
        try {
            Class<?> hub = Class.forName("dev.youtiao.aemobile.net.ChatHub");
            accept = hub.getMethod(
                "acceptFormatted",
                IChatComponent.class,
                String.class,
                String.class,
                String.class,
                String.class,
                boolean.class,
                int.class);
            enabled = true;
            GTNHChat.LOG.info("已接上 AEMobile 聊天桥");
        } catch (Throwable t) {
            accept = null;
            enabled = false;
            GTNHChat.LOG.info("未发现 AEMobile，手机端桥接关闭");
        }
    }

    /**
     * 广播前调用，把这条消息的元数据交给手机端。
     *
     * @param line   即将广播的组件，必须与随后 {@code sendChatMsg} 的参数是同一个对象
     * @param player 说话的人；成就公告是达成成就的玩家，服务器广播留空
     * @param world  世界显示名，没有就传空串
     * @param raw    玩家原始输入（未格式化、未加颜色码），用于手机端去重
     * @param origin 来源服务器显示名：本服消息传本服的，跨服消息传发送端的
     * @param remote 是否是别的服务器转发过来的
     * @param kind   {@link #KIND_GAME} / {@link #KIND_SERVER} / {@link #KIND_ACHIEVEMENT}
     */
    public static void push(IChatComponent line, String player, String world, String raw,
                            String origin, boolean remote, int kind) {
        check();
        if (!enabled || line == null) return;
        try {
            accept.invoke(null, line, player, world, raw, origin, remote, kind);
        } catch (Throwable t) {
            // 桥只是旁路：出错不能让聊天本身失败
            if (Config.debug) GTNHChat.LOG.warn("手机端桥接调用失败", t);
        }
    }
}
