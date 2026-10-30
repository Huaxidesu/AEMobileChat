package com.huaxidesu.gtnchat.client;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.stats.Achievement;
import net.minecraft.stats.AchievementList;
import net.minecraft.stats.StatFileWriter;

import com.huaxidesu.gtnchat.config.Config;
import com.huaxidesu.gtnchat.util.Reflect;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 客户端成就检测。
 *
 * <p>1.7.10 的成就是纯客户端的：解锁只写本地的统计文件，服务端既不记录也不广播，
 * 也没有任何事件可以监听。所以只能由客户端自己比对统计文件，把新解锁的成就报给服务端，
 * 再由服务端负责公告和跨服转发。
 *
 * <p>每秒扫一次已解锁列表。统计文件是哈希表，几百个成就的查询在微秒级，不占用渲染线程。
 */
@SideOnly(Side.CLIENT)
public final class ClientAchievements {

    private static final Set<String> SEEN = new HashSet<String>();
    private static boolean primed;
    private static String lastName;
    private static int counter;

    private ClientAchievements() {}

    public static void onTick() {
        if (++counter % 20 != 0) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc == null ? null : mc.thePlayer;
        if (player == null) {
            SEEN.clear();
            primed = false;
            lastName = null;
            ClientNet.reset();
            return;
        }
        if (!Config.achievementEnabled) return;

        String name = player.getCommandSenderName();
        if (lastName != null && !lastName.equals(name)) {
            // 换了账号：重新建立基线，避免把别人的成就当成自己的
            SEEN.clear();
            primed = false;
        }
        lastName = name;

        StatFileWriter stats = player.getStatFileWriter();
        if (stats == null) return;

        for (Object o : AchievementList.achievementList) {
            if (!(o instanceof Achievement)) continue;
            Achievement achievement = (Achievement) o;
            boolean unlocked;
            try {
                unlocked = stats.hasAchievementUnlocked(achievement);
            } catch (Throwable t) {
                continue;
            }
            if (!unlocked) continue;
            if (!SEEN.add(achievement.statId)) continue;
            // 第一次扫描只建立基线：进服前就已经解锁的成就不是这次拿到的
            if (!primed) continue;
            ClientNet.reportAchievement(achievement.statId, displayName(achievement));
        }
        primed = true;
    }

    /** 成就名用客户端语言显示，服务端拿到的会是英文，所以名字随上报一起发过去。 */
    private static String displayName(Achievement achievement) {
        try {
            return Reflect.statName(achievement);
        } catch (Throwable t) {
            return "";
        }
    }
}
