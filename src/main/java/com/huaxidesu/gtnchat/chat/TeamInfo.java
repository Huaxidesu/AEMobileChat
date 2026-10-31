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
 * <p>按 UUID 缓存几秒：团队名和职位变化不频繁，而每条聊天都查一次反射不划算。
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

        Info(String name, String desc, String rank) {
            this.name = name;
            this.desc = desc;
            this.rank = rank;
        }
    }

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
            available = true;
            GTNHChat.LOG.info("已接上 ServerUtilities 团队");
        } catch (Throwable t) {
            available = false;
            GTNHChat.LOG.info("未发现 ServerUtilities，队伍前缀关闭");
        }
    }

    /** 调试用：把中间状态摊开，排查「有队伍但不显示」这类问题 */
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
            return "hasTeam=" + has + " title=[" + title + "] desc=[" + desc + "] status=" + status;
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
            // 这种情况用简介兜底，否则「有队伍但一个字都不显示」，看着像功能没做。
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

            return new Info(name, desc, rank);
        } catch (Throwable t) {
            if (Config.debug) GTNHChat.LOG.warn("读取团队信息失败", t);
            return null;
        }
    }

    /** IChatComponent → 纯文本（团队名可以带颜色码，这里去掉） */
    private static String plain(Object component) {
        if (component == null) return "";
        try {
            Method m = component.getClass().getMethod("getUnformattedText");
            Object v = m.invoke(component);
            String s = v == null ? "" : String.valueOf(v);
            return s.replace("&", "").replace("\u00a7", "").trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
