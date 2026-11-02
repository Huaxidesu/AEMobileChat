package com.huaxidesu.gtnchat.client;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import com.huaxidesu.gtnchat.config.Config;
import com.huaxidesu.gtnchat.net.ChatNet;
import com.huaxidesu.gtnchat.relay.ChatRelay;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 客户端与服务器之间的少量交互。
 *
 * <p>服务器在玩家进服时发一个握手帧；客户端只有收到握手后才会上报数据。
 * 这样服务端没装本模组时，客户端不会往一个没人监听的通道发东西。
 */
@SideOnly(Side.CLIENT)
public final class ClientNet {

    private static boolean serverSupported;

    private ClientNet() {}

    public static void handle(byte[] data) {
        try {
            if (data == null || data.length < 6) return;
            if (data[0] != 'G' || data[1] != 'T' || data[2] != 'C' || data[3] != '1') return;
            byte type = data[5];
            if (type == ChatRelay.TYPE_HELLO) {
                boolean first = !serverSupported;
                serverSupported = true;
                // 回执：服务端据此知道「这台客户端装了本模组」，成就公告才有来源。
                // 顺带让 AEMobile 判断本地成就是不是该由它自己发（见 ChatRelay.clientModPresent）。
                if (first) sendReady();
            }
        } catch (Throwable ignored) {}
    }

    /** 告诉服务端：本客户端装了本模组。只在每次连上后发一次。 */
    private static void sendReady() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8);
            DataOutputStream out = new DataOutputStream(bos);
            out.write(new byte[] { 'G', 'T', 'C', '1' });
            out.writeByte(Config.relayProtocol);
            out.writeByte(ChatRelay.TYPE_CLIENT_READY);
            // 能力位：服务端据此决定聊天要不要给名字留边（老服务端读不到会忽略）
            out.writeByte(ChatHeadsProbe.caps());
            out.flush();
            ChatNet.sendToServer(bos.toByteArray());
        } catch (Throwable ignored) {}
    }

    public static void reset() {
        serverSupported = false;
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
