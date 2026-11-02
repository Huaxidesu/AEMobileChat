package com.huaxidesu.gtnchat.net;

import java.util.ArrayList;
import java.util.List;

/**
 * 能力位：一套「我能做什么」的位标记，用来替代「版本号对不对」。
 *
 * <p>协议版本号只回答一个问题 —— <b>帧结构还读得懂吗</b>。除此之外的一切差别都应该落在
 * 能力位上：服务端加了新功能就多一个位，客户端不认识就忽略；客户端升级了就多报一个位，
 * 服务端不认识也忽略。这样老客户端不会因为服务端升了功能就被迫跟着升级。
 *
 * <p>两个方向的位各自独立，互不复用编号：
 * <ul>
 *   <li>{@code C_*} —— 客户端在 {@code TYPE_CLIENT_READY} 里上报，说明这台客户端自己能做什么；</li>
 *   <li>{@code S_*} —— 服务端在 {@code TYPE_HELLO} 里下发，说明这台服务端能提供什么。</li>
 * </ul>
 *
 * <p><b>位一旦定义就不要改语义</b>，需要改的话另起一个新位。老客户端会用旧含义解释它，
 * 改了就等于悄悄把功能弄坏。
 */
public final class Caps {

    // ---------------------------------------------------------------- 客户端 → 服务端

    /** 名字前会画头像（NecroTempus 的 ChatHeads），服务端据此决定要不要留边 */
    public static final int C_CHAT_HEADS = 1;
    /** 会通过本模组的通道上报成就（1.1.0 起就有，这里补一个正式的定义） */
    public static final int C_ACH_REPORT = 1 << 1;
    /** 本版本认识的全部客户端位，给「对面什么都没报」时兜底用 */
    public static final int C_ALL = C_CHAT_HEADS | C_ACH_REPORT;

    // ---------------------------------------------------------------- 服务端 → 客户端

    /** 服务端会按 {@code chat.chat-heads} 的设定给名字留边 */
    public static final int S_HEADS_PAD = 1;
    /** 跨服转发开着 */
    public static final int S_RELAY = 1 << 1;
    /** 成就公告开着 */
    public static final int S_ACH = 1 << 2;
    /** NEI 物品链接桥可用 */
    public static final int S_NEI = 1 << 3;
    /** 接上了 AEMobile 手机端桥 */
    public static final int S_APP = 1 << 4;
    /** 本版本认识的全部服务端位，给「老服务端什么都没报」时兜底用 */
    public static final int S_ALL = S_HEADS_PAD | S_RELAY | S_ACH | S_NEI | S_APP;

    private Caps() {}

    /** {@code /gtchat status} 与 debug 日志用：把这台客户端的能力说成人话 */
    public static String describeClient(int caps) {
        List<String> out = new ArrayList<String>(2);
        if ((caps & C_CHAT_HEADS) != 0) out.add("头像");
        if ((caps & C_ACH_REPORT) != 0) out.add("成就");
        return join(out);
    }

    /** 同上，服务端方向 */
    public static String describeServer(int caps) {
        List<String> out = new ArrayList<String>(5);
        if ((caps & S_HEADS_PAD) != 0) out.add("头像留边");
        if ((caps & S_RELAY) != 0) out.add("跨服");
        if ((caps & S_ACH) != 0) out.add("成就公告");
        if ((caps & S_NEI) != 0) out.add("NEI");
        if ((caps & S_APP) != 0) out.add("手机端");
        return join(out);
    }

    private static String join(List<String> parts) {
        if (parts.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append('/');
            sb.append(p);
        }
        return sb.toString();
    }
}
