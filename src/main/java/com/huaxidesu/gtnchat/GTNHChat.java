package com.huaxidesu.gtnchat;

import java.io.File;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.IChatComponent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.huaxidesu.gtnchat.bridge.MobileBridge;
import com.huaxidesu.gtnchat.chat.ChatFormat;
import com.huaxidesu.gtnchat.chat.ChatHeads;
import com.huaxidesu.gtnchat.chat.TeamInfo;
import com.huaxidesu.gtnchat.chat.WorldNames;
import com.huaxidesu.gtnchat.client.ClientSetup;
import com.huaxidesu.gtnchat.command.CommandGTNH;
import com.huaxidesu.gtnchat.config.Config;
import com.huaxidesu.gtnchat.config.Rule;
import com.huaxidesu.gtnchat.nei.NeiLink;
import com.huaxidesu.gtnchat.net.ChatNet;
import com.huaxidesu.gtnchat.relay.ChatRelay;
import com.huaxidesu.gtnchat.util.MainThread;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;

/**
 * 聊天格式化与跨服转发的入口。
 *
 * <p>服务端负责渲染与转发；客户端只做一件服务端无法完成的事 —— 检测成就解锁并上报。
 * 客户端不装本模组也能正常进服，只是不会有成就公告。
 */
@Mod(
    modid = Tags.MODID,
    name = Tags.MODNAME,
    version = Tags.VERSION,
    acceptableRemoteVersions = "*",
    acceptableSaveVersions = "*")
public class GTNHChat {

    public static final Logger LOG = LogManager.getLogger(Tags.MODNAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Config.init(new File(event.getModConfigurationDirectory(), "gtnchat"));
        ChatNet.register();
        NeiLink.available();

        MinecraftForge.EVENT_BUS.register(new ForgeHandlers());
        FMLCommonHandler.instance().bus().register(new FmlHandlers());

        if (FMLCommonHandler.instance().getSide().isClient()) {
            ClientSetup.init();
        }
        LOG.info("已加载，服务器标识: " + Config.serverId + "，跨服转发: " + (Config.relayEnabled ? "开" : "关"));
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandGTNH());
        // Multiverse 的 worlds.yml 相对服务端根目录，启动时定位一次
        try {
            WorldNames.init(event.getServer().getFile("."));
        } catch (Throwable t) {
            WorldNames.init(null);
        }
    }

    // ------------------------------------------------------------ 事件

    /** Forge 总线：玩家聊天、NEI 的客户端上报包。 */
    public static final class ForgeHandlers {

        @SubscribeEvent
        public void onChat(ServerChatEvent event) {
            if (event.player == null || event.message == null) return;
            final EntityPlayerMP player = event.player;
            try {
                // 这里跑在网络线程：渲染在此完成，广播回主线程
                if (Config.debug) LOG.info("收到聊天，当前线程: " + Thread.currentThread().getName());
                final String message = ChatFormat.sanitize(event.message);
                final Rule rule = ChatFormat.pick(player);

                ChatFormat.Ctx ctx = new ChatFormat.Ctx();
                ctx.player = player.getCommandSenderName();
                ctx.world = ChatFormat.worldName(player);
                ctx.server = Config.serverDisplay;
                ctx.message = message;
                ctx.keepClick = true;

                // 队伍名放在人名前面，悬停里带简介和职位（见 style.yml 的 team 片段）
                TeamInfo.Info team = TeamInfo.of(player);
                if (team != null) {
                    ctx.team = team.name;
                    ctx.teamDesc = team.desc;
                    ctx.teamRank = team.rank;
                    // 队伍名和玩家名都用 ServerUtilities 里配的队伍颜色
                    ctx.teamColor = team.color;
                }

                final IChatComponent line = ChatFormat.render(ctx, rule);
                final String ruleName = rule.name;
                final String world = ctx.world;
                final String who = ctx.player;

                // 取消原版广播，改由本模组统一发一条格式化后的消息。
                event.setCanceled(true);
                MainThread.submit(new Runnable() {

                    @Override
                    public void run() {
                        // 手机端要在广播前拿到元数据，否则只能把整行当成服务器消息
                        MobileBridge.push(line, who, world, message, Config.serverDisplay, false,
                            MobileBridge.KIND_GAME, team == null ? "" : team.name,
                            team == null ? "" : team.color);
                        MinecraftServer.getServer().getConfigurationManager().sendChatMsg(line);
                        ChatRelay.forwardChat(player, message, ruleName);
                    }
                });
            } catch (Throwable t) {
                LOG.warn("处理玩家聊天失败", t);
            }
        }
    }

    /** FML 总线：tick、玩家进服。 */
    public static final class FmlHandlers {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) MainThread.drain();
            // 只 stat 一次 mtime，文件没变就什么都不做
            if (event.phase == TickEvent.Phase.END) WorldNames.reloadIfChanged();
        }

        @SubscribeEvent
        public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.player instanceof EntityPlayerMP)) return;
            ChatRelay.sendHello((EntityPlayerMP) event.player);
        }

        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.player == null) return;
            // 同名重进（离线模式换人）不应沿用上一个玩家的「客户端装了本模组」状态
            ChatRelay.clearClientMod(event.player.getCommandSenderName());
            // 客户端能力同理：玩家退出后，头像留边的判定要随之更新
            ChatHeads.clear(event.player.getCommandSenderName());
        }
    }
}
