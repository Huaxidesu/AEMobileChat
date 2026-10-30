package com.huaxidesu.gtnchat.net;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;

import com.huaxidesu.gtnchat.GTNHChat;
import com.huaxidesu.gtnchat.client.ClientNet;
import com.huaxidesu.gtnchat.relay.ChatRelay;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.Unpooled;

/**
 * 模组自己的网络通道，只承载本模组定义的帧。
 *
 * <p>服务端方向的帧有两类来源：客户端上报的成就，以及代理转发过来的其它服务器的消息。
 * 两者字节格式相同，靠帧头区分，因此这里只做一次拆包再分发。
 */
public final class ChatNet {

    public static final String CHANNEL = "gtnchat";

    private static FMLEventChannel channel;

    private ChatNet() {}

    public static void register() {
        try {
            channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL);
            channel.register(new ServerHandler());
            if (FMLCommonHandler.instance().getSide().isClient()) channel.register(new ClientHandler());
        } catch (Throwable t) {
            GTNHChat.LOG.warn("网络通道注册失败，跨服与成就上报将不可用", t);
        }
    }

    public static void sendToPlayer(EntityPlayerMP player, byte[] data) {
        if (channel == null || player == null || data == null) return;
        try {
            channel.sendTo(new FMLProxyPacket(Unpooled.wrappedBuffer(data), CHANNEL), player);
        } catch (Throwable t) {
            GTNHChat.LOG.warn("向玩家发送数据失败", t);
        }
    }

    public static void sendToServer(byte[] data) {
        if (channel == null || data == null) return;
        try {
            channel.sendToServer(new FMLProxyPacket(Unpooled.wrappedBuffer(data), CHANNEL));
        } catch (Throwable t) {
            GTNHChat.LOG.warn("向服务端发送数据失败", t);
        }
    }

    /** 服务端收到的帧：客户端上报、代理转发。 */
    public static final class ServerHandler {

        @SubscribeEvent
        public void onPacket(FMLNetworkEvent.ServerCustomPacketEvent event) {
            try {
                byte[] bytes = new byte[event.packet.payload().readableBytes()];
                event.packet.payload().getBytes(0, bytes);
                ChatRelay.handle(bytes, playerOf(event.manager));
            } catch (Throwable t) {
                if (com.huaxidesu.gtnchat.config.Config.debug) GTNHChat.LOG.warn("处理网络帧失败", t);
            }
        }

        /** 用连接反查玩家：不依赖帧里写的名字。 */
        private static EntityPlayerMP playerOf(NetworkManager manager) {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null || manager == null) return null;
            for (Object o : server.getConfigurationManager().playerEntityList) {
                if (!(o instanceof EntityPlayerMP)) continue;
                EntityPlayerMP p = (EntityPlayerMP) o;
                if (p.playerNetServerHandler != null && p.playerNetServerHandler.netManager == manager) return p;
            }
            return null;
        }
    }

    /** 客户端收到的帧：只有握手。 */
    public static final class ClientHandler {

        @SubscribeEvent
        public void onPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
            try {
                byte[] bytes = new byte[event.packet.payload().readableBytes()];
                event.packet.payload().getBytes(0, bytes);
                ClientNet.handle(bytes);
            } catch (Throwable ignored) {}
        }
    }
}
