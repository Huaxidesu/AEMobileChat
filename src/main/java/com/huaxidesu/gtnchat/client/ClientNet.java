package com.huaxidesu.gtnchat.client;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import com.huaxidesu.gtnchat.GTNHChat;
import com.huaxidesu.gtnchat.config.Config;
import com.huaxidesu.gtnchat.net.Caps;
import com.huaxidesu.gtnchat.net.ChatNet;
import com.huaxidesu.gtnchat.relay.ChatRelay;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 客户端与服务器之间的少量交互。
 *
 * <p>服务器在玩家进服时发一个握手帧；客户端只有收到握手后才会上报数据。
 * 这样服务端没装本模组时，客户端不会往一个没人监听的通道发东西。
 *
 * <p>握手是<b>双向能力交换</b>的：服务端在 {@code HELLO} 里报「我能提供什么」和「最低接受哪个协议」，
 * 客户端在 {@code READY} 里报「我能做什么」。两边都不认识的位一律忽略，
 * 所以加功能不需要改协议号，也不用逼对面升级。
 */
@SideOnly(Side.CLIENT)
public final class ClientNet {

    private static boolean serverSupported;
    /** 服务端报的能力位；没报过时按「全都有」处理 */
    private static int serverCaps;
    /** 服务端还能读懂的最低协议版本 */
    private static int serverMin = 1;

    private ClientNet() {}

    public static void handle(byte[] data) {
        try {
            if (data == null || data.length < 6) return;
            if (data[0] != 'G' || data[1] != 'T' || data[2] != 'C' || data[3] != '1') return;
            if (data[5] != ChatRelay.TYPE_HELLO) return;

            // 1.2.0 起帧尾多两个字节：服务端能力位 + 最低接受协议。
            // 老服务端只发 6 个字节 —— 什么都没报，按「都有」处理，
            // 不能因为客户端升级了反而少功能。
            if (data.length >= 8) {
                serverCaps = data[6] & 0xFF;
                serverMin = data[7] & 0xFF;
            } else {
                serverCaps = Caps.S_ALL;
                serverMin = 1;
            }

            boolean first = !serverSupported;
            serverSupported = true;
            if (first) {
                warnIfTooOld();
                // 回执：服务端据此知道「这台客户端装了本模组」，成就公告才有来源。
                // 顺带让 AEMobile 判断本地成就是不是该由它自己发（见 ChatRelay.clientModPresent）。
                sendReady();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 服务端要求的最低协议比本客户端高。
     *
     * <p>这不是「连不上」：聊天格式化、跨服这些老功能照常工作，只是服务端新加的东西可能用不上。
     * 所以只写一条日志说明，不做任何阻拦 —— 以前这种情况是静默失效，玩家只会觉得「模组坏了」。
     */
    private static void warnIfTooOld() {
        int mine = Config.relayProtocol;
        if (serverMin <= mine) return;
        GTNHChat.LOG.warn("服务端最低要求协议 " + serverMin + "，本客户端是 " + mine
            + "：连接与聊天不受影响，但服务端新增的功能可能用不上");
    }

    /** 本客户端的能力位，见 {@link Caps} */
    private static int caps() {
        return ChatHeadsProbe.caps() | Caps.C_ACH_REPORT;
    }

    /** 告诉服务端：本客户端装了本模组，以及它能做什么。只在每次连上后发一次。 */
    private static void sendReady() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(10);
            DataOutputStream out = new DataOutputStream(bos);
            out.write(new byte[] { 'G', 'T', 'C', '1' });
            out.writeByte(Config.relayProtocol);
            out.writeByte(ChatRelay.TYPE_CLIENT_READY);
            // 能力位两个字节（低字节在前）：1.1.2 只发了一个，老服务端也只读一个，
            // 所以这里扩宽不用抬协议号 —— 尾部追加字节，读不到就算了。
            int caps = caps();
            out.writeByte(caps & 0xFF);
            out.writeByte((caps >>> 8) & 0xFF);
            out.flush();
            ChatNet.sendToServer(bos.toByteArray());
        } catch (Throwable ignored) {}
    }

    public static void reset() {
        serverSupported = false;
        serverCaps = 0;
        serverMin = 1;
    }

    // ------------------------------------------------------------ 查询服务端能力

    /** 这台服务端有没有某项能力（{@link Caps#S_HEADS_PAD} 等） */
    public static boolean has(int cap) {
        return (serverCaps & cap) != 0;
    }

    public static int serverCaps() {
        return serverCaps;
    }

    public static int serverMinProtocol() {
        return serverMin;
    }

    public static void reportAchievement(String statId, String localizedName) {
        if (!serverSupported || statId == null || statId.isEmpty()) return;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(bos);
            out.write(new byte[] { 'G', 'T', 'C', '1' });
            out.writeByte(Config.relayProtocol);
            out.writeByte(ChatRelay.TYPE_REPORT);
            out.writeUTF(statId);
            out.writeUTF(localizedName == null ? "" : localizedName);
            out.flush();
            ChatNet.sendToServer(bos.toByteArray());
        } catch (Throwable ignored) {}
    }
}
