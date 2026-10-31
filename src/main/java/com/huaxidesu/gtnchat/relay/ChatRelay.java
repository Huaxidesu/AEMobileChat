package com.huaxidesu.gtnchat.relay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.Achievement;
import net.minecraft.stats.AchievementList;
import net.minecraft.util.IChatComponent;

import com.huaxidesu.gtnchat.GTNHChat;
import com.huaxidesu.gtnchat.bridge.MobileBridge;
import com.huaxidesu.gtnchat.chat.ChatFormat;
import com.huaxidesu.gtnchat.config.Config;
import com.huaxidesu.gtnchat.config.Rule;
import com.huaxidesu.gtnchat.nei.NeiLink;
import com.huaxidesu.gtnchat.net.ChatNet;
import com.huaxidesu.gtnchat.util.MainThread;
import com.huaxidesu.gtnchat.util.Reflect;

/**
 * 跨服消息的编解码与收发。
 *
 * <p>传输走代理自带的 {@code BungeeCord} 通道的 {@code Forward} 子通道：后端把消息写给代理，
 * 代理转发给同一网络内的其它后端，接收端在自己的通道上收到。代理端不需要装任何东西。
 *
 * <p>帧里带来源标识和消息序号：来源标识用来丢弃绕回来的自己的消息，序号用来兜底去重。
 * 协议版本不一致的帧直接丢弃，避免两代版本混跑时出现看不懂的字段。
 */
public final class ChatRelay {

    public static final byte TYPE_CHAT = 1;
    public static final byte TYPE_ACHIEVEMENT = 2;
    public static final byte TYPE_NEI = 3;
    public static final byte TYPE_HELLO = 4;
    public static final byte TYPE_REPORT = 5;

    private static final byte[] MAGIC = { 'G', 'T', 'C', '1' };
    private static final int MAX_FRAME = 30000;
    private static final long REPORT_INTERVAL_MS = 3000L;

    private static final Map<Long, Boolean> SEEN = new LinkedHashMap<Long, Boolean>(256, 0.75f, false) {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > 512;
        }
    };

    private static final Map<String, Long> LAST_REPORT = new HashMap<String, Long>();

    private ChatRelay() {}

    // ------------------------------------------------------------------ 发送

    /** 把一条玩家聊天发给其它服务器。必须在主线程调用。 */
    public static void forwardChat(EntityPlayerMP sender, String message, String ruleName) {
        if (!Config.relayEnabled) return;
        try {
            Out out = new Out(TYPE_CHAT);
            out.writeUtf(sender.getCommandSenderName());
            out.writeUtf(ChatFormat.worldName(sender));
            out.writeUtf(message);
            out.writeUtf(ruleName == null ? "" : ruleName);
            send(out, sender);
        } catch (Throwable t) {
            GTNHChat.LOG.warn("跨服聊天发送失败", t);
        }
    }

    public static void forwardAchievement(String player, String achievement) {
        if (!Config.relayEnabled || !Config.achievementEnabled) return;
        try {
            Out out = new Out(TYPE_ACHIEVEMENT);
            out.writeUtf(player);
            out.writeUtf(achievement);
            send(out, null);
        } catch (Throwable t) {
            GTNHChat.LOG.warn("跨服成就发送失败", t);
        }
    }

    public static void forwardNeiItem(String player, byte[] payload) {
        if (!Config.relayEnabled || !Config.neiEnabled || payload == null) return;
        try {
            Out out = new Out(TYPE_NEI);
            out.writeUtf(player);
            out.writeBytes(payload);
            send(out, null);
        } catch (Throwable t) {
            GTNHChat.LOG.warn("跨服物品链接发送失败", t);
        }
    }

    /** 进服握手：告诉客户端服务端装了本模组，可以上报成就。 */
    public static void sendHello(EntityPlayerMP player) {
        byte[] frame = new byte[] { 'G', 'T', 'C', '1', (byte) Config.relayProtocol, TYPE_HELLO };
        ChatNet.sendToPlayer(player, frame);
    }

    private static void send(Out out, EntityPlayerMP preferred) throws Exception {
        EntityPlayerMP carrier = preferred != null ? preferred : anyOnline();
        if (carrier == null) return;

        byte[] frame = out.toByteArray();
        if (frame.length > MAX_FRAME) {
            GTNHChat.LOG.warn("跨服帧超过上限，已丢弃: " + frame.length);
            return;
        }
        markSeen(out.id);

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(frame.length + 32);
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeUTF("Forward");
            dos.writeUTF("ALL");
            dos.writeUTF(Config.relayChannel);
            dos.writeShort(frame.length);
            dos.write(frame);
            dos.flush();

            carrier.playerNetServerHandler.sendPacket(new S3FPacketCustomPayload("BungeeCord", bos.toByteArray()));
            if (Config.debug) GTNHChat.LOG.info("跨服帧已交给代理: type=" + out.type + " bytes=" + frame.length);
        } catch (Throwable t) {
            GTNHChat.LOG.warn("写入代理通道失败", t);
        }
    }

    private static EntityPlayerMP anyOnline() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return null;
        List<?> players = server.getConfigurationManager().playerEntityList;
        for (Object o : players) {
            if (o instanceof EntityPlayerMP) return (EntityPlayerMP) o;
        }
        return null;
    }

    // ------------------------------------------------------------------ 接收

    /** 由网络通道调用，可能在任何线程；渲染和广播一律排到主线程。 */
    public static void handle(byte[] data, EntityPlayerMP sender) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            for (byte b : MAGIC) {
                if (in.readByte() != b) return;
            }
            int protocol = in.readUnsignedByte();
            byte type = in.readByte();
            if (protocol != Config.relayProtocol) {
                if (Config.debug) GTNHChat.LOG.info("跨服协议版本不一致，丢弃: " + protocol);
                return;
            }
            if (type == TYPE_REPORT) {
                handleReport(in, sender);
                return;
            }
            if (type == TYPE_HELLO) return;

            String originId = in.readUTF();
            String originDisplay = in.readUTF();
            long id = in.readLong();

            if (originId.equals(Config.serverId)) return;
            if (alreadySeen(id)) return;
            markSeen(id);

            switch (type) {
                case TYPE_CHAT:
                    handleChat(in, originDisplay);
                    break;
                case TYPE_ACHIEVEMENT:
                    handleAchievement(in);
                    break;
                case TYPE_NEI:
                    handleNei(in, originDisplay);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            if (Config.debug) GTNHChat.LOG.warn("跨服帧解析失败", t);
        }
    }

    private static void handleChat(DataInputStream in, String originDisplay) throws Exception {
        final String sender = in.readUTF();
        final String world = in.readUTF();
        final String message = in.readUTF();
        final String ruleName = in.readUTF();

        MainThread.submit(new Runnable() {

            @Override
            public void run() {
                ChatFormat.Ctx ctx = new ChatFormat.Ctx();
                ctx.player = sender;
                ctx.world = world;
                ctx.server = originDisplay;
                ctx.message = message;
                ctx.keepClick = Config.relayKeepClick;
                IChatComponent line = ChatFormat.render(ctx, rule(ruleName));
                // 手机端据此把消息归到玩家气泡，并标注来自哪台服务器
                MobileBridge.push(line, sender, world, message, originDisplay, true, MobileBridge.KIND_GAME);
                MinecraftServer.getServer().getConfigurationManager().sendChatMsg(line);
            }
        });
    }

    private static void handleAchievement(DataInputStream in) throws Exception {
        final String player = in.readUTF();
        final String achievement = in.readUTF();
        if (!Config.achievementEnabled) return;
        MainThread.submit(new Runnable() {

            @Override
            public void run() {
                ChatFormat.Ctx ctx = new ChatFormat.Ctx();
                ctx.player = player;
                ctx.server = Config.serverDisplay;
                IChatComponent line = ChatFormat.render(ctx, Config.achievementLayout.replace("{achievement}", achievement));
                MobileBridge.push(line, player, "", achievement, Config.serverDisplay, false, MobileBridge.KIND_ACHIEVEMENT);
                MinecraftServer.getServer().getConfigurationManager().sendChatMsg(line);
            }
        });
    }

    private static void handleNei(DataInputStream in, String originDisplay) throws Exception {
        final String player = in.readUTF();
        final byte[] payload = new byte[in.readInt()];
        in.readFully(payload);
        if (!Config.neiEnabled) return;
        MainThread.submit(new Runnable() {

            @Override
            public void run() {
                NeiLink.reinject(player, payload, originDisplay);
            }
        });
    }

    /** 客户端上报的成就。发送者以连接为准，帧里不带名字，避免伪造。 */
    private static void handleReport(DataInputStream in, EntityPlayerMP sender) throws Exception {
        final String statId = in.readUTF();
        String localized = in.readUTF();
        if (sender == null || !Config.achievementEnabled) return;

        String key = sender.getCommandSenderName();
        long now = System.currentTimeMillis();
        Long last = LAST_REPORT.get(key);
        if (last != null && now - last < REPORT_INTERVAL_MS) return;
        LAST_REPORT.put(key, now);

        final String name = localized != null && !localized.isEmpty() ? localized : resolveAchievement(statId);
        if (name == null || name.isEmpty()) return;
        final String player = key;

        MainThread.submit(new Runnable() {

            @Override
            public void run() {
                ChatFormat.Ctx ctx = new ChatFormat.Ctx();
                ctx.player = player;
                ctx.server = Config.serverDisplay;
                IChatComponent line = ChatFormat.render(ctx, Config.achievementLayout.replace("{achievement}", name));
                MobileBridge.push(line, player, "", name, Config.serverDisplay, false, MobileBridge.KIND_ACHIEVEMENT);
                MinecraftServer.getServer().getConfigurationManager().sendChatMsg(line);
                forwardAchievement(player, name);
            }
        });
    }

    private static String resolveAchievement(String statId) {
        if (statId == null) return "";
        for (Object o : AchievementList.achievementList) {
            if (!(o instanceof Achievement)) continue;
            Achievement a = (Achievement) o;
            if (statId.equals(a.statId)) {
                String name = Reflect.statName(a);
                if (!name.isEmpty()) return name;
                break;
            }
        }
        return statId.replaceFirst("^achievement\\.", "");
    }

    private static Rule rule(String name) {
        for (Rule r : Config.rules) {
            if (r.name.equals(name)) return r;
        }
        return Config.rules.isEmpty() ? null : Config.rules.get(Config.rules.size() - 1);
    }

    // ------------------------------------------------------------------ 调试

    /** 造一条「来自其它服务器」的聊天，用来在没有第二个服时验证接收链路。 */
    public static void debugInject(String originId, String originDisplay, String sender, String message) {
        try {
            Out out = new Out(TYPE_CHAT, originId, originDisplay);
            out.writeUtf(sender);
            out.writeUtf("测试世界");
            out.writeUtf(message);
            out.writeUtf("default");
            handle(out.toByteArray(), null);
        } catch (Throwable t) {
            GTNHChat.LOG.warn("注入测试消息失败", t);
        }
    }

    // ------------------------------------------------------------------ 去重

    private static synchronized boolean alreadySeen(long id) {
        return SEEN.containsKey(id);
    }

    private static synchronized void markSeen(long id) {
        SEEN.put(id, Boolean.TRUE);
    }

    // ------------------------------------------------------------------ 帧

    /** 帧写入器：固定帧头 + 自增序号，序号同时用于去重。 */
    private static final class Out {

        final byte type;
        final long id;
        private final ByteArrayOutputStream bos;
        private final DataOutputStream out;

        Out(byte type) throws Exception {
            this(type, Config.serverId, Config.serverDisplay);
        }

        Out(byte type, String originId, String originDisplay) throws Exception {
            this.type = type;
            this.id = System.nanoTime() ^ (long) (Math.random() * 0x7fffffffL);
            this.bos = new ByteArrayOutputStream(128);
            this.out = new DataOutputStream(bos);
            out.write(MAGIC);
            out.writeByte(Config.relayProtocol);
            out.writeByte(type);
            out.writeUTF(originId);
            out.writeUTF(originDisplay);
            out.writeLong(id);
        }

        void writeUtf(String s) throws Exception {
            out.writeUTF(s == null ? "" : s);
        }

        void writeBytes(byte[] b) throws Exception {
            out.writeInt(b.length);
            out.write(b);
        }

        byte[] toByteArray() throws Exception {
            out.flush();
            return bos.toByteArray();
        }
    }
}
