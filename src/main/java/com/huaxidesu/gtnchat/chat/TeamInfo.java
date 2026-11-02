package com.huaxidesu.gtnchat.chat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;

import com.huaxidesu.gtnchat.GTNHChat;
import com.huaxidesu.gtnchat.config.Config;

/**
 * 玩家所属的 ServerUtilities 团队。
 *
 * <p>1.7.10 的团队系统在 ServerUtilities 里（FTBUtilities 的 1.7.10 backport），
 * 没有任何跨模组的事件可以监听，只能反射查：
 * {@code Universe.getPlayer(...).team} → {@code ForgeTeam.getTitle() / getDesc() / getHighestStatus(...)}。
 *
 * <p>没装 ServerUtilities 时整体停用，返回 null，聊天照常渲染（只是没有队伍前缀）。
 *
 * <p>按 UUID 缓存几秒：团队名和职位变化不频繁，而每条聊天都反射查询开销过大。
 */
public final class TeamInfo {

    /** 一条团队信息（不可变） */
    public static final class Info {
        /** 团队名（纯文本，已去颜色码） */
        public final String name;
        /** 团队简介，没写就是空串 */
        public final String desc;
        /** 职位：队长 / 管理员 / 成员 */
        public final String rank;
        /** 队伍颜色码字符（{@code 9} / {@code b} …），给 {@code {teamcolor}} 用 */
        public final String color;

        Info(String name, String desc, String rank, String color) {
            this.name = name;
            this.desc = desc;
            this.rank = rank;
            this.color = color == null || color.isEmpty() ? DEFAULT_COLOR : color;
        }
    }

    /** 取不到队伍颜色时用的码：青色，暗背景上比 ServerUtilities 默认的深蓝清楚得多 */
    public static final String DEFAULT_COLOR = "b";

    private static final long TTL_MS = 5000L;

    private static boolean checked;
    private static boolean available;
    private static Method mGetUniverse;
    private static Method mGetPlayer;
    private static Method mHasTeam;
    private static Method mGetTitle;
    private static Method mGetDesc;
    private static Method mGetStatus;
    private static Field fTeam;
    private static Method mStatusName;
    /** {@code ForgeTeam.getColor()} → EnumTeamColor；{@code EnumTeamColor.getColor()} → EnumChatFormatting */
    private static Method mGetColor;
    private static Method mColorFormatting;

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<UUID, Cached>();

    private static final class Cached {
        final Info info;
        final long at;

        Cached(Info info, long at) {
            this.info = info;
            this.at = at;
        }
    }

    private TeamInfo() {}

    /** 配置重载后清空缓存（团队设置可能跟着改） */
    public static void invalidate() {
        CACHE.clear();
    }

    public static boolean available() {
        check();
        return available;
    }

    private static synchronized void check() {
        if (checked) return;
        checked = true;
        try {
            Class<?> universe = Class.forName("serverutils.lib.data.Universe");
            Class<?> forgePlayer = Class.forName("serverutils.lib.data.ForgePlayer");
            Class<?> forgeTeam = Class.forName("serverutils.lib.data.ForgeTeam");
            Class<?> status = Class.forName("serverutils.lib.EnumTeamStatus");

            mGetUniverse = universe.getMethod("getNullable");
            mGetPlayer = universe.getMethod("getPlayer", net.minecraft.command.ICommandSender.class);
            mHasTeam = forgePlayer.getMethod("hasTeam");
            fTeam = forgePlayer.getField("team");
            mGetTitle = forgeTeam.getMethod("getTitle");
            mGetDesc = forgeTeam.getMethod("getDesc");
            mGetStatus = forgeTeam.getMethod("getHighestStatus", forgePlayer);
            mStatusName = status.getMethod("name");
            try {
                mGetColor = forgeTeam.getMethod("getColor");
            } catch (Throwable ignored) {
                // 旧版本 ServerUtilities 没有队伍颜色：使用默认色
            }
            available = true;
            GTNHChat.LOG.info("已接上 ServerUtilities 团队");
        } catch (Throwable t) {
            available = false;
            GTNHChat.LOG.info("未发现 ServerUtilities，队伍前缀关闭");
        }
    }

    /** 调试用：输出中间状态，排查「有队伍但不显示」这类问题 */
    public static String debug(EntityPlayerMP player) {
        check();
        if (!available) return "ServerUtilities 未加载";
        if (player == null) return "玩家为 null";
        try {
            Object universe = mGetUniverse.invoke(null);
            if (universe == null) return "Universe 为 null";
            Object fp = mGetPlayer.invoke(universe, player);
            if (fp == null) return "ForgePlayer 为 null";
            Object has = mHasTeam.invoke(fp);
            Object team = fTeam.get(fp);
            if (team == null) return "hasTeam=" + has + " team=null";
            String title = plain(mGetTitle.invoke(team));
            Object desc = mGetDesc.invoke(team);
            Object status = mGetStatus.invoke(team, fp);
            return "hasTeam=" + has + " title=[" + title + "] desc=[" + desc + "] status=" + status
                + " color=[" + teamColor(team) + "] raw=[" + rawColor(team) + "]";
        } catch (Throwable t) {
            return "error=" + t;
        }
    }

    /** 取玩家所在团队；没有团队或没装模组返回 null */
    public static Info of(EntityPlayerMP player) {
        check();
        if (!available || player == null) return null;
        UUID id = player.getUniqueID();
        long now = System.currentTimeMillis();
        Cached cached = CACHE.get(id);
        if (cached != null && now - cached.at < TTL_MS) return cached.info;

        Info info = resolve(player);
        CACHE.put(id, new Cached(info, now));
        if (CACHE.size() > 512) {
            // 简单清理：只在明显变多时全清，团队信息本来就不该有这么多
            for (Map.Entry<UUID, Cached> e : CACHE.entrySet()) {
                if (now - e.getValue().at > TTL_MS) CACHE.remove(e.getKey());
            }
        }
        return info;
    }

    private static Info resolve(EntityPlayerMP player) {
        try {
            Object universe = mGetUniverse.invoke(null);
            if (universe == null) return null;
            Object fp = mGetPlayer.invoke(universe, player);
            if (fp == null) return null;
            Object has = mHasTeam.invoke(fp);
            if (!Boolean.TRUE.equals(has)) return null;
            Object team = fTeam.get(fp);
            if (team == null) return null;

            String name = plain(mGetTitle.invoke(team));

            String desc = "";
            try {
                Object d = mGetDesc.invoke(team);
                if (d instanceof String) desc = (String) d;
            } catch (Throwable ignored) {}

            // ServerUtilities 的单人队默认没有名字（title 为空），只有简介。
            // 这种情况用简介作为后备，否则有队伍时完全不显示名称，表现为功能缺失。
            if (name.isEmpty()) {
                String d = desc == null ? "" : desc.trim();
                if (!d.isEmpty() && d.length() <= 16) name = d;
            }
            if (name.isEmpty()) return null;

            String rank = "成员";
            try {
                Object status = mGetStatus.invoke(team, fp);
                if (status != null) {
                    String n = String.valueOf(mStatusName.invoke(status));
                    if ("OWNER".equals(n)) rank = "队长";
                    else if ("MOD".equals(n)) rank = "管理员";
                }
            } catch (Throwable ignored) {}

            return new Info(name, desc, rank, teamColor(team));
        } catch (Throwable t) {
            if (Config.debug) GTNHChat.LOG.warn("读取团队信息失败", t);
            return null;
        }
    }

    /**
     * 队伍在 ServerUtilities 里配的颜色，返回 {@code §} 后面的那个码字符（{@code 9} / {@code d} …）。
     *
     * <p>链子是 {@code ForgeTeam.getColor() → EnumTeamColor}，再取
     * {@code EnumTeamColor.getEnumChatFormatting()}（**不是** {@code getColor()}，
     * 那个返回的是 {@code Color4I} 的 RGB 对象，转不出码字符 —— 2026-10-31 已确认，
     * 表现是「改了队伍颜色但聊天里一直是默认青色」）。
     */
    private static String teamColor(Object team) {
        if (mGetColor == null) return DEFAULT_COLOR;
        try {
            Object enumTeamColor = mGetColor.invoke(team);
            if (enumTeamColor == null) return DEFAULT_COLOR;
            if (mColorFormatting == null) {
                mColorFormatting = enumTeamColor.getClass().getMethod("getEnumChatFormatting");
            }
            Object formatting = mColorFormatting.invoke(enumTeamColor);
            if (formatting == null) return DEFAULT_COLOR;
            // EnumChatFormatting.toString() 有的版本给 "d"，有的给 "§d"，统一成单字符
            String code = String.valueOf(formatting).trim();
            if (code.startsWith("\u00a7")) code = code.substring(1);
            if (code.length() != 1) return DEFAULT_COLOR;
            char c = Character.toLowerCase(code.charAt(0));
            return "0123456789abcdef".indexOf(c) >= 0 ? String.valueOf(c) : DEFAULT_COLOR;
        } catch (Throwable t) {
            return DEFAULT_COLOR;
        }
    }

    /** 调试用：把 {@code EnumTeamColor} 和它给的格式枚举原样打出来，确认颜色码长什么样 */
    private static String rawColor(Object team) {
        if (mGetColor == null) return "no-getColor";
        try {
            Object enumTeamColor = mGetColor.invoke(team);
            if (enumTeamColor == null) return "null";
            Object formatting = enumTeamColor.getClass().getMethod("getEnumChatFormatting").invoke(enumTeamColor);
            return String.valueOf(enumTeamColor) + "/" + String.valueOf(formatting);
        } catch (Throwable t) {
            return "error=" + t;
        }
    }

    /** IChatComponent → 纯文本（团队名可以带颜色码，这里去掉） */
    /**
     * 取组件的纯文本。
     *
     * <p><b>必须试 SRG 名</b>：{@code ForgeTeam.getTitle()} 返回的是 {@code IChatComponent}，
     * reobf 之后 {@code getUnformattedText()} 在运行时叫 {@code func_150260_c}，
     * 只按 MCP 名反射永远抛 {@code NoSuchMethodException} —— 表现就是「队伍明明有名字，
     * 却一直显示描述」（2026-10-31 发现：标题「华溪岛」显示成描述「温馨的小家」）。
     */
    private static String plain(Object component) {
        if (component == null) return "";
        if (component instanceof String) return clean((String) component);
        for (String name : new String[] { "getUnformattedText", "func_150260_c" }) {
            try {
                Method m = component.getClass().getMethod(name);
                Object v = m.invoke(component);
                if (v != null) return clean(String.valueOf(v));
            } catch (Throwable ignored) {
                // 换下一个名字
            }
        }
        return "";
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("&", "").replace("\u00a7", "").trim();
    }
}
