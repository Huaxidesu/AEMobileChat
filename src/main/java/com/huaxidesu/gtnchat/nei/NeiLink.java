package com.huaxidesu.gtnchat.nei;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

import com.huaxidesu.gtnchat.GTNHChat;
import com.huaxidesu.gtnchat.bridge.MobileBridge;
import com.huaxidesu.gtnchat.chat.ChatFormat;
import com.huaxidesu.gtnchat.config.Config;
import com.huaxidesu.gtnchat.relay.ChatRelay;
import com.huaxidesu.gtnchat.util.MainThread;


/**
 * NEI 物品链接的跨服桥。
 *
 * <p>NEI 的物品链接不是聊天消息：客户端把物品数据发给服务端，服务端生成一个短标识，
 * 把数据推给本服所有客户端，再广播一条带点击动作的聊天。也就是说链接只在「生成它的那台服务器」
 * 上有效 —— 直接把文字转到别的服，点开只会提示找不到数据。
 *
 * <p>所以这里做两件事：拦下客户端发往 NEI 的物品数据，随聊天一起转给其它服务器；
 * 接收端把同一份数据推给自己服的客户端，再广播一条指向该数据的链接。
 *
 * <p>NEI 没有对外公开这套数据格式，全部通过反射调用；NEI 缺失或版本不匹配时整体跳过，
 * 不影响普通聊天。
 */
public final class NeiLink {

    private static boolean checked;
    private static boolean available;

    private static int c2sChatItemLink = -1;
    private static int s2cCacheBookmark = -1;
    private static Constructor<?> packetCtor;
    private static Method mWriteString;
    private static Method mWriteNbt;
    private static Method mSendToPlayer;

    private NeiLink() {}

    public static boolean available() {
        check();
        return available;
    }

    /** 注入是否生效：生效时目标类上会多出本模组的方法。 */
    public static boolean hookApplied() {
        try {
            Class<?> target = Class.forName("codechicken.nei.NEIServerUtils");
            for (java.lang.reflect.Method m : target.getDeclaredMethods()) {
                if (m.getName().contains("gtnchat$")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 允许在模组加载完成后重新探测一次，避免初始化顺序导致误判。 */
    public static synchronized void reset() {
        checked = false;
    }

    private static synchronized void check() {
        if (checked) return;
        checked = true;
        try {
            Class<?> c2s = Class.forName("codechicken.nei.PacketIDs$C2S");
            c2sChatItemLink = c2s.getField("SEND_CHAT_ITEM_LINK").getInt(null);

            Class<?> s2c = Class.forName("codechicken.nei.PacketIDs$S2C");
            s2cCacheBookmark = s2c.getField("CACHE_BOOKMARK_DATA").getInt(null);

            Class<?> packet = Class.forName("codechicken.lib.packet.PacketCustom");
            packetCtor = packet.getConstructor(Object.class, int.class);
            mWriteString = packet.getMethod("writeString", String.class);
            mWriteNbt = packet.getMethod("writeNBTTagCompound", NBTTagCompound.class);
            mSendToPlayer = packet.getMethod("sendToPlayer", EntityPlayer.class);
            available = true;
        } catch (Throwable t) {
            available = false;
            GTNHChat.LOG.info("NEI 桥初始化失败，物品链接跨服已关闭: " + t);
        }
    }

    /**
     * 本服玩家分享物品时调用（由 Mixin 挂在 NEI 的服务端入口上）。
     *
     * <p>NEI 会在网络线程里走完整个流程，这里只把数据复制一份丢给主线程，
     * 不影响 NEI 自己的推送和广播。
     */
    public static void onLocalShare(EntityPlayerMP sender, NBTTagCompound payload) {
        if (sender == null || payload == null) return;
        if (!Config.relayEnabled || !Config.neiEnabled) return;
        final byte[] bytes = writeNbt(payload);
        if (bytes == null) return;
        final String name = sender.getCommandSenderName();
        MainThread.submit(new Runnable() {

            @Override
            public void run() {
                ChatRelay.forwardNeiItem(name, bytes);
            }
        });
    }

    /** 在接收端把物品数据推给本服客户端，并广播一条可点击的聊天。 */
    public static void reinject(String player, byte[] payload, String originDisplay) {
        if (!available() || !Config.neiEnabled) return;
        try {
            NBTTagCompound tag = readNbt(payload);
            if (tag == null) return;

            String id = UUID.randomUUID().toString().substring(0, 8);
            Object custom = packetCtor.newInstance("NEI", s2cCacheBookmark);
            mWriteString.invoke(custom, id);
            mWriteNbt.invoke(custom, tag);

            List<?> players = MinecraftServer.getServer().getConfigurationManager().playerEntityList;
            for (Object o : players) {
                if (!(o instanceof EntityPlayerMP)) continue;
                try {
                    mSendToPlayer.invoke(custom, o);
                } catch (Throwable ignored) {}
            }

            ChatFormat.Ctx ctx = new ChatFormat.Ctx();
            ctx.player = player;
            ctx.server = originDisplay;
            ctx.keepClick = true;
            ctx.item = itemComponent(tag, id);
            IChatComponent line = ChatFormat.render(ctx, Config.neiLayout);
            // 物品名与悬停提示由手机端从组件里还原，这里只补「谁、来自哪台服务器」
            MobileBridge.push(line, player, "", "", originDisplay, true, MobileBridge.KIND_GAME, "");
            MinecraftServer.getServer().getConfigurationManager().sendChatMsg(line);
        } catch (Throwable t) {
            GTNHChat.LOG.warn("转发物品链接失败", t);
        }
    }

    private static IChatComponent itemComponent(NBTTagCompound payload, String id) {
        IChatComponent fallback = new ChatComponentText("[物品]");
        try {
            if (!payload.hasKey("item")) return fallback;
            ItemStack stack = ItemStack.loadItemStackFromNBT(payload.getCompoundTag("item"));
            if (stack == null) return fallback;

            ChatComponentText box = new ChatComponentText("[" + stack.getDisplayName() + "]");
            ChatStyle style = new ChatStyle();
            style.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/nei_bookmark " + id));
            style.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, tooltip(stack)));
            box.setChatStyle(style);
            return box;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static IChatComponent tooltip(ItemStack stack) {
        ChatComponentText root = new ChatComponentText("");
        try {
            List<?> lines = stack.getTooltip(null, false);
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) root.appendSibling(new ChatComponentText("\n"));
                Object o = lines.get(i);
                if (o != null) root.appendSibling(new ChatComponentText(String.valueOf(o)));
            }
        } catch (Throwable ignored) {}
        return root;
    }

    private static NBTTagCompound readNbt(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            return CompressedStreamTools.read(in);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调试用：把 NBT 序列化回去，便于日志比对。 */
    public static byte[] writeNbt(NBTTagCompound tag) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            CompressedStreamTools.write(tag, new DataOutputStream(bos));
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
}
