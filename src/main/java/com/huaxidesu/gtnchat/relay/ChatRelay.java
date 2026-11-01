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
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import com.huaxidesu.gtnchat.GTNHChat;
import com.huaxidesu.gtnchat.bridge.MobileBridge;
import com.huaxidesu.gtnchat.chat.ChatFormat;
import com.huaxidesu.gtnchat.chat.TeamInfo;
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
    /** 手机端（AEMobile App）发的聊天：它不走 {@code ServerChatEvent}，要单独一帧 */
    public static final byte TYPE_APP = 6;
    /**
     * 客户端收到握手后的回执：证明这个玩家的客户端装了本模组。
     *
     * <p>服务端本来只知道「有人上报过成就」，那要等玩家真的拿到成就才知道。
     * 有了这条回执，AEMobile 才能判断「这台客户端能不能自己播成就」——
     * 不能的话由它自己发，否则手机端一条成就都收不到（客户端没装本模组时就是这样）。
     */
    public static final byte TYPE_CLIENT_READY = 7;

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
    /** 玩家名 → 收到客户端回执的时间。只在收到回执时写，断开不清（重进会再回执一次）。 */
    private static final Map<String, Long> CLIENT_MOD = new HashMap<String, Long>();

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
            // 团队信息跟在后面：旧版本没有这三个字段，接收端按剩余字节判断
            TeamInfo.Info team = TeamInfo.of(sender);
            out.writeUtf(team == null ? "" : team.name);
            out.writeUtf(team == null ? "" : team.desc);
            out.writeUtf(team == null ? "" : team.rank);
            // 队伍颜色是最后加的（1.1.0）：老接收端读到 EOF 就当没有，不会解析失败
            out.writeUtf(team == null ? "" : team.color);
            send(out, sender);
        } catch (Throwable t) {
            GTNHChat.LOG.warn("跨服聊天发送失败", t);
        }
    }

    /**
     * 把手机端（AEMobile App）发的一条聊天发给其它服务器。
     *
     * <p>手机端的消息由 AEMobile 自己广播，**不经过 {@code ServerChatEvent}**，
     * 所以 {@link #forwardChat} 收不到它 —— 群组服里手机端说的话只有它连的那台子服能看到。
     * AEMobile 广播后会反射调用本方法补上（见 AEMobile 的 {@code net/GtnChatBridge}）。
     *
     * <p>必须在主线程调用。没有在线玩家可以当载体时不转发：代理的 {@code Forward} 通道
     * 要靠一条玩家连接才写得出去。
     */
    public static void forwardAppChat(String name, String text) {
        if (!Config.relayEnabled) return;
        if (name == null || name.isEmpty() || text == null || text.isEmpty()) return;
        try {
            Out out = new Out(TYPE_APP);
            out.writeUtf(name);
            out.writeUtf(text);
            send(out, null);
        } catch (Throwable t) {
            GTNHChat.LOG.warn("跨服手机端聊天发送失败", t);
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
            if (type == TYPE_CLIENT_READY) {
                markClientMod(sender);
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
                case TYPE_APP:
                    handleApp(in, originDisplay);
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
        // 团队三项是后加的：老版本发的帧到这就结束了，读不到就当没有。
        // 不用 available() 判断 —— 在 Thermos 上它不可靠，会一直返回 0。
        String t0 = "", t1 = "", t2 = "", t3 = "";
        try {
            t0 = in.readUTF();
            t1 = in.readUTF();
            t2 = in.readUTF();
            t3 = in.readUTF();
        } catch (java.io.EOFException ignored) {
        }
        final String team = t0;
        final String teamDesc = t1;
        final String teamRank = t2;
        // 队伍颜色（1.1.0）：不传的话手机端那条消息的名字会从队伍色掉回白色，
        // 和本服聊天的样子对不上 —— 跨服消息最容易看出这个不一致
        final String teamColor = t3;

        // 控制台留一条带来源的记录：sendChatMsg 那条只有渲染后的文本，
        // 服主翻日志时不好确认是从哪台服过来的
        if (Config.relayLog) {
            GTNHChat.LOG.info("[跨服] " + originDisplay + " → " + Config.serverDisplay
                + ": <" + sender + "> " + ChatFormat.sanitize(message));
        }

        MainThread.submit(new Runnable() {

            @Override
            public void run() {
                ChatFormat.Ctx ctx = new ChatFormat.Ctx();
                ctx.player = sender;
                ctx.world = world;
                ctx.server = originDisplay;
                ctx.message = message;
                ctx.keepClick = Config.relayKeepClick;
                ctx.team = team;
                ctx.teamDesc = teamDesc;
                ctx.teamRank = teamRank;
                IChatComponent line = ChatFormat.render(ctx, rule(ruleName));
                // 手机端据此把消息归到玩家气泡，并标注来自哪台服务器
                MobileBridge.push(line, sender, world, message, originDisplay, true, MobileBridge.KIND_GAME,
                    team, teamColor);
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

    /**
     * 接收别的子服转发过来的手机端聊天。
     *
     * <p>游戏内渲染成 {@code [来源服][AEMobile][名字] 正文}，与 AEMobile 在本服的样式一致，
     * 这样服主一眼能看出这条不是游戏里打的字。手机端只收正文：组件里那些前缀
     * 手机端会自己拼，重复传过去会显示成「名字: [天空树][AEMobile][名字] 正文」。
     */
    private static void handleApp(DataInputStream in, String originDisplay) throws Exception {
        final String name = in.readUTF();
        final String text = in.readUTF();
        if (Config.relayLog) {
            GTNHChat.LOG.info("[跨服] " + originDisplay + " → " + Config.serverDisplay
                + ": <" + name + ">(手机) " + ChatFormat.sanitize(text));
        }
        MainThread.submit(new Runnable() {

            @Override
            public void run() {
                IChatComponent line = renderAppLine(originDisplay, name, text);
                MobileBridge.push(line, name, "", text, originDisplay, true, MobileBridge.KIND_APP, "");
                MinecraftServer.getServer().getConfigurationManager().sendChatMsg(line);
            }
        });
    }

    /** 手机端消息在别的服上的样子，颜色与 AEMobile 本服广播保持一致 */
    private static IChatComponent renderAppLine(String originDisplay, String name, String text) {
        ChatComponentText head = new ChatComponentText("[" + originDisplay + "]");
        head.getChatStyle().setColor(EnumChatFormatting.GOLD);

        ChatComponentText tag = new ChatComponentText("[AEMobile]");
        tag.getChatStyle().setColor(EnumChatFormatting.GRAY);
        head.appendSibling(tag);

        ChatComponentText who = new ChatComponentText("[" + name + "] ");
        who.getChatStyle().setColor(EnumChatFormatting.AQUA);
        head.appendSibling(who);

        ChatComponentText body = new ChatComponentText(ChatFormat.sanitize(text));
        body.getChatStyle().setColor(EnumChatFormatting.WHITE);
        head.appendSibling(body);
        return head;
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

    // ------------------------------------------------------------ 客户端是否装了本模组

    /** 收到客户端回执：这个玩家的客户端装了本模组，成就公告由客户端上报驱动。 */
    private static void markClientMod(EntityPlayerMP sender) {
        if (sender == null) return;
        synchronized (CLIENT_MOD) {
            CLIENT_MOD.put(sender.getCommandSenderName(), System.currentTimeMillis());
        }
        if (Config.debug) GTNHChat.LOG.info("客户端回执：" + sender.getCommandSenderName() + " 装了 GTNHChat");
    }

    /** 玩家退出时清掉，避免同名重进（离线模式换人）时沿用上一个人的状态 */
    public static void clearClientMod(String player) {
        if (player == null) return;
        synchronized (CLIENT_MOD) {
            CLIENT_MOD.remove(player);
        }
    }

    /**
     * 这个玩家的客户端到底装没装本模组（成就公告靠客户端上报，只有装了才播得出来）。
     *
     * <p>给 AEMobile 反射调用，决定本地成就由谁发：
     * <ul>
     *   <li>{@code 1} —— 客户端装了，本模组会播，手机端让路；</li>
     *   <li>{@code 0} —— 客户端没装（或压根没回执），手机端自己发；</li>
     *   <li>{@code -1} —— 不知道：老版本本模组没有回执机制，保持「让路」的老行为。</li>
     * </ul>
     */
    public static int clientModPresent(String player) {
        if (player == null || player.isEmpty()) return -1;
        synchronized (CLIENT_MOD) {
            return CLIENT_MOD.containsKey(player) ? 1 : 0;
        }
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
            // 带上假的团队信息，方便验证「队伍名 → 悬停 → 手机端标签」整条链路
            out.writeUtf("测试队伍");
            out.writeUtf("这是一支测试队伍");
            out.writeUtf("队长");
            handle(out.toByteArray(), null);
        } catch (Throwable t) {
            GTNHChat.LOG.warn("注入测试消息失败", t);
        }
    }

    /**
     * 测试用：伪造一条「别的子服的手机端消息」，走完整的接收路径
     * （渲染 → 交给手机端 → 广播）。本机只有一台后端，没法真跑两台来验证
     * {@link #handleApp} 的渲染与来源标签。
     */
    public static void debugInjectApp(String originId, String originDisplay, String name, String text) {
        try {
            Out out = new Out(TYPE_APP, originId, originDisplay);
            out.writeUtf(name);
            out.writeUtf(text);
            handle(out.toByteArray(), null);
        } catch (Throwable t) {
            GTNHChat.LOG.warn("注入手机端测试消息失败", t);
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
