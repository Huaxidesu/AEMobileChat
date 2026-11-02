package com.huaxidesu.gtnchat.chat;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.huaxidesu.gtnchat.config.Config;

/**
 * 「客户端会在名字前面画头像」这件事的状态汇总。
 *
 * <p>头像不是服务端画的（NecroTempus 的 ChatHeads 模块），服务端既画不了也改不了它的位置：
 * 它把 8×8 的头像画在「玩家名第一次出现的那个字符」上，占 9px 宽、上沿比文字还高 1px。
 * 于是名字前面的方括号 `]` 和头像之间是 0px，后面那个字离头像只有 1px —— 整行看起来是挤在一起的。
 *
 * <p>服务端唯一能做的就是在这个位置留边。但留边会让没开头像的客户端多出一个空格，
 * 所以先要知道「对面到底画不画」：客户端回执里带一个能力位
 * （{@link com.huaxidesu.gtnchat.net.Caps#C_CHAT_HEADS}），
 * 这里按玩家记下来；配置里再用 {@code chat.chat-heads} 决定用哪种口径。
 *
 * <p>整条聊天是广播同一个组件，没法按人分开排，所以判定结果是「全局生效」：
 * 只要有一个上报了头像的客户端在线，所有人看到的都是留过边的版本。
 */
public final class ChatHeads {

    /** 上报了「名字前会画头像」的客户端（按玩家名，离线即清） */
    private static final Set<String> CLIENTS = Collections
        .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private ChatHeads() {}

    public static void report(String player, int caps) {
        if (player == null || player.isEmpty()) return;
        if ((caps & com.huaxidesu.gtnchat.net.Caps.C_CHAT_HEADS) != 0) CLIENTS.add(player);
        else CLIENTS.remove(player);
    }

    /** 玩家退出时清掉：离线模式同名换人不能沿用上一个人的客户端能力 */
    public static void clear(String player) {
        if (player == null) return;
        CLIENTS.remove(player);
    }

    public static void clearAll() {
        CLIENTS.clear();
    }

    public static int reporting() {
        return CLIENTS.size();
    }

    /** 当前这条广播要不要给名字留边。 */
    public static boolean active() {
        int mode = Config.chatHeads;
        if (mode == Config.CHAT_HEADS_ON) return true;
        if (mode == Config.CHAT_HEADS_OFF) return false;
        return !CLIENTS.isEmpty();
    }

    /**
     * 名字前留一个空格（4px）。
     *
     * <p>只补左边：右边客户端自己留了 1px，而且布局里名字后面通常紧跟 `: `，
     * 再补一个空格会变成 `名字 : 正文`。左边是真正的 0px，那才是贴着方括号的那一边。
     */
    public static String withMargin(String name) {
        if (name == null || name.isEmpty()) return name;
        return " " + name;
    }

    /** {@code /gtchat status} 用的一句话说明 */
    public static String describe() {
        String mode;
        if (Config.chatHeads == Config.CHAT_HEADS_ON) mode = "强制开";
        else if (Config.chatHeads == Config.CHAT_HEADS_OFF) mode = "关";
        else mode = "自动";
        return mode + "（当前" + (active() ? "留边" : "不留边") + "，上报客户端 " + CLIENTS.size() + " 台）";
    }
}
