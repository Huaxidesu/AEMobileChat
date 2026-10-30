package com.huaxidesu.gtnchat.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

import com.huaxidesu.gtnchat.Tags;
import com.huaxidesu.gtnchat.chat.ChatFormat;
import com.huaxidesu.gtnchat.config.Config;
import com.huaxidesu.gtnchat.config.Rule;
import com.huaxidesu.gtnchat.nei.NeiLink;
import com.huaxidesu.gtnchat.relay.ChatRelay;

/** {@code /gtchat}：重载配置、查看状态、在没人聊天时造几条消息做验证。 */
public class CommandGTNH implements ICommand {

    private static final List<String> SUB = Arrays.asList("reload", "version", "status", "test");

    @Override
    public String getCommandName() {
        return "gtchat";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/gtchat reload | version | status | test <chat|achievement|relay|nei> ...";
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List getCommandAliases() {
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            reply(sender, getCommandUsage(sender));
            return;
        }
        String sub = args[0].toLowerCase();
        if ("reload".equals(sub)) {
            Config.reload();
            if (Config.lastError == null) {
                reply(sender, "配置已重载：规则 " + Config.rules.size() + " 条，片段 " + Config.styles.size() + " 个");
            } else {
                reply(sender, "配置重载失败，沿用旧值：" + Config.lastError);
            }
            return;
        }
        if ("version".equals(sub)) {
            reply(sender, Tags.MODNAME + " " + Tags.VERSION);
            return;
        }
        if ("status".equals(sub)) {
            reply(sender, "服务器标识: " + Config.serverId + "（" + Config.serverDisplay + "）");
            reply(sender, "跨服转发: " + (Config.relayEnabled ? "开" : "关") + "，通道 " + Config.relayChannel + "，协议 " + Config.relayProtocol);
            reply(sender, "成就公告: " + (Config.achievementEnabled ? "开" : "关")
                + "，NEI 桥: " + (NeiLink.available() ? (NeiLink.hookApplied() ? "可用（已注入）" : "可用（未注入）") : "不可用"));
            return;
        }
        if ("test".equals(sub)) {
            test(sender, args);
            return;
        }
        reply(sender, getCommandUsage(sender));
    }

    private void test(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, "/gtchat test chat <内容> | achievement <成就ID> | relay <内容> | nei");
            return;
        }
        String kind = args[1].toLowerCase();
        if ("chat".equals(kind)) {
            String text = join(args, 2);
            EntityPlayerMP player = asPlayer(sender);
            if (player == null) {
                // 没有玩家时把渲染结果直接回显，方便在控制台核对格式
                ChatFormat.Ctx ctx = new ChatFormat.Ctx();
                ctx.player = "Steve";
                ctx.world = "主世界";
                ctx.server = Config.serverDisplay;
                ctx.message = ChatFormat.sanitize(text);
                ctx.keepClick = true;
                reply(sender, describe(ChatFormat.render(ctx, Config.rules.isEmpty() ? null : Config.rules.get(Config.rules.size() - 1))));
                return;
            }
            broadcastAs(player, text);
            reply(sender, "已按 " + player.getCommandSenderName() + " 的身份广播一条聊天");
            return;
        }
        if ("achievement".equals(kind)) {
            String id = args.length > 2 ? join(args, 2) : "achievement.mineWood";
            ChatFormat.Ctx ctx = new ChatFormat.Ctx();
            ctx.player = asPlayer(sender) == null ? "Steve" : asPlayer(sender).getCommandSenderName();
            ctx.server = Config.serverDisplay;
            IChatComponent line = ChatFormat.render(ctx, Config.achievementLayout.replace("{achievement}", id));
            MinecraftServer.getServer().getConfigurationManager().sendChatMsg(line);
            ChatRelay.forwardAchievement(ctx.player, id);
            reply(sender, "已广播成就公告: " + id);
            return;
        }
        if ("relay".equals(kind)) {
            String text = join(args, 2);
            ChatRelay.debugInject("test-origin", "测试服", "Steve", text);
            reply(sender, "已注入一条来自其它服务器的聊天");
            return;
        }
        if ("nei".equals(kind)) {
            reply(sender, "NEI 桥: " + (NeiLink.available() ? "可用" : "未检测到 NEI"));
            return;
        }
        reply(sender, "未知的测试类型: " + kind);
    }

    private static void broadcastAs(EntityPlayerMP player, String message) {
        Rule rule = ChatFormat.pick(player);
        ChatFormat.Ctx ctx = new ChatFormat.Ctx();
        ctx.player = player.getCommandSenderName();
        ctx.world = ChatFormat.worldName(player);
        ctx.server = Config.serverDisplay;
        ctx.message = ChatFormat.sanitize(message);
        ctx.keepClick = true;
        IChatComponent line = ChatFormat.render(ctx, rule);
        MinecraftServer.getServer().getConfigurationManager().sendChatMsg(line);
        ChatRelay.forwardChat(player, ctx.message, rule.name);
    }

    private static EntityPlayerMP asPlayer(ICommandSender sender) {
        if (sender instanceof EntityPlayerMP) return (EntityPlayerMP) sender;
        List<?> players = MinecraftServer.getServer().getConfigurationManager().playerEntityList;
        for (Object o : players) {
            if (o instanceof EntityPlayerMP) return (EntityPlayerMP) o;
        }
        return null;
    }

    private static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    /** 把组件树摊平成一行，用于在控制台核对渲染结果。 */
    private static String describe(IChatComponent root) {
        StringBuilder sb = new StringBuilder();
        describe(root, sb);
        return sb.toString();
    }

    private static void describe(IChatComponent node, StringBuilder sb) {
        if (node == null) return;
        String text = node.getUnformattedText();
        sb.append('<').append(text).append('>');
        ChatStyle style = node.getChatStyle();
        if (style != null) {
            if (style.getColor() != null) sb.append("(color=").append(style.getColor()).append(')');
            if (style.getChatClickEvent() != null) {
                sb.append("(click=").append(style.getChatClickEvent().getAction()).append(':')
                    .append(style.getChatClickEvent().getValue()).append(')');
            }
            if (style.getChatHoverEvent() != null) sb.append("(hover)");
        }
        for (Object child : node.getSiblings()) {
            if (child instanceof IChatComponent) describe((IChatComponent) child, sb);
        }
    }

    private static void reply(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText("[GTNHChat] " + text));
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return sender.canCommandSenderUseCommand(2, "gtchat");
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<String>();
            for (String s : SUB) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
            return out;
        }
        if (args.length == 2 && "test".equalsIgnoreCase(args[0])) {
            return Arrays.asList("chat", "achievement", "relay", "nei");
        }
        return null;
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return false;
    }

    @Override
    public int compareTo(Object o) {
        return 0;
    }
}
