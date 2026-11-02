package com.huaxidesu.gtnchat.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

import com.huaxidesu.gtnchat.Tags;
import com.huaxidesu.gtnchat.bridge.MobileBridge;
import com.huaxidesu.gtnchat.chat.ChatFormat;
import com.huaxidesu.gtnchat.chat.ChatHeads;
import com.huaxidesu.gtnchat.chat.TeamInfo;
import com.huaxidesu.gtnchat.config.Config;
import com.huaxidesu.gtnchat.config.Rule;
import com.huaxidesu.gtnchat.nei.NeiLink;
import com.huaxidesu.gtnchat.net.Caps;
import com.huaxidesu.gtnchat.net.Clients;
import com.huaxidesu.gtnchat.relay.ChatRelay;

/** {@code /gtchat}：重载配置、查看状态、在无人聊天时生成消息用于验证。 */
public class CommandGTNH implements ICommand {

    private static final List<String> SUB = Arrays.asList("reload", "version", "status", "test");

    @Override
    public String getCommandName() {
        return "gtchat";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/gtchat reload | version | status | test <chat|achievement|relay|nei|item> ...";
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
            reply(sender, "跨服转发: " + (Config.relayEnabled ? "开" : "关") + "，通道 " + Config.relayChannel
                + "，协议 " + Config.relayProtocol + "（接受 " + Config.relayMinProtocol + "~" + Config.relayProtocol + "）");
            reply(sender, "服务端能力: " + Caps.describeServer(ChatRelay.serverCaps()));
            reply(sender, "客户端回执: " + Clients.summary());
            reply(sender, "成就公告: " + (Config.achievementEnabled ? "开" : "关")
                + "，NEI 桥: " + (NeiLink.available() ? (NeiLink.hookApplied() ? "可用（已注入）" : "可用（未注入）") : "不可用"));
            reply(sender, "手机端桥: " + (MobileBridge.enabled()
                ? "已接上 AEMobile，能力 " + mobileCaps()
                : "未发现 AEMobile（手机端相关功能全部关闭，本模组独立可用）"));
            reply(sender, "聊天头像兼容: " + ChatHeads.describe());
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
            reply(sender, "/gtchat test chat <内容> | json <内容> | bench [次数] | achievement <成就ID> | relay <内容> | nei | item [物品名]");
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
            // 和真实路径一致：手机端只收成就名，系统行自己拼玩家名
            MobileBridge.push(line, ctx.player, "", id, Config.serverDisplay, false, MobileBridge.KIND_ACHIEVEMENT);
            MinecraftServer.getServer().getConfigurationManager().sendChatMsg(line);
            ChatRelay.forwardAchievement(ctx.player, id);
            reply(sender, "已广播成就公告: " + id);
            return;
        }
        if ("relay".equals(kind)) {
            String text = join(args, 2);
            ChatRelay.debugInject("test-origin", "测试服", "Steve", text);
            // 没有玩家时把接收端渲染出来的样子回显，方便核对来源服务器名与格式
            ChatFormat.Ctx ctx = sample();
            ctx.server = "测试服";
            ctx.message = ChatFormat.sanitize(text);
            reply(sender, "接收端渲染: " + describe(ChatFormat.render(ctx, Config.rules.isEmpty() ? null : Config.rules.get(Config.rules.size() - 1))));
            return;
        }
        if ("nei".equals(kind)) {
            reply(sender, "NEI 桥: " + (NeiLink.available() ? (NeiLink.hookApplied() ? "可用（已注入）" : "可用（未注入）") : "未检测到 NEI"));
            return;
        }
        if ("app".equals(kind)) {
            // 本机只有一台后端，跨服接收端只能靠伪造一帧来验证
            String name = args.length > 2 ? args[2] : "Tester";
            String text = args.length > 3 ? join(args, 3) : "手机端跨服测试";
            ChatRelay.debugInjectApp("test-origin", "测试服", name, text);
            reply(sender, "已按「测试服 → " + Config.serverDisplay + "」注入手机端消息: "
                + name + ": " + text);
            return;
        }
        if ("team".equals(kind)) {
            EntityPlayerMP target = args.length > 2
                ? MinecraftServer.getServer().getConfigurationManager().func_152612_a(join(args, 2))
                : asPlayer(sender);
            reply(sender, target == null ? "玩家不在线" : TeamInfo.debug(target));
            return;
        }
        if ("item".equals(kind)) {
            // 构造一条和 NEI 真实分享同构的消息：文本 + /nei_bookmark 点击 + 多行悬停。
            // 本机没有真人用 NEI 分享时，靠这条验证手机端能否把物品从正文中识别出来。
            String name = args.length > 2 ? join(args, 2) : "石头";
            ChatComponentText item = new ChatComponentText("[" + name + "]");
            ChatStyle st = new ChatStyle();
            st.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/nei_bookmark test" + System.nanoTime()));
            st.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText("GT " + name + "\n可堆叠 64")));
            item.setChatStyle(st);

            ChatFormat.Ctx ctx = sample();
            ctx.server = "测试服";
            ctx.item = item;
            IChatComponent line = ChatFormat.render(ctx, Config.neiLayout);
            MobileBridge.push(line, "Steve", "", "", "测试服", true, MobileBridge.KIND_GAME);
            MinecraftServer.getServer().getConfigurationManager().sendChatMsg(line);
            reply(sender, "已广播物品链接: " + name);
            return;
        }
        if ("json".equals(kind)) {
            // 第一个参数可以是规则名，用来看指定规则（比如管理员）渲染成什么样
            Rule rule = null;
            String text;
            if (args.length > 2 && findRule(args[2]) != null) {
                rule = findRule(args[2]);
                text = join(args, 3);
            } else {
                text = join(args, 2);
            }
            ChatFormat.Ctx ctx = sample();
            ctx.message = ChatFormat.sanitize(text);
            IChatComponent line = ChatFormat.render(ctx, rule != null ? rule : (Config.rules.isEmpty() ? null : Config.rules.get(Config.rules.size() - 1)));
            String json = com.huaxidesu.gtnchat.util.Reflect.toJson(line);
            reply(sender, json.isEmpty() ? "序列化失败" : json);
            return;
        }
        if ("bench".equals(kind)) {
            bench(sender, args.length > 2 ? args[2] : "20000");
            return;
        }
        reply(sender, "未知的测试类型: " + kind);
    }

    /** 手机端实际能拿到的能力，和 AEMobile 握手下发的 `caps` 是同一套口径 */
    private static String mobileCaps() {
        StringBuilder sb = new StringBuilder("chat");
        if (TeamInfo.available()) sb.append("/team");
        sb.append("/world");
        if (Config.relayEnabled) sb.append("/relay");
        return sb.toString();
    }

    private static Rule findRule(String name) {
        for (Rule r : Config.rules) {
            if (r.name.equals(name)) return r;
        }
        return null;
    }

    private static ChatFormat.Ctx sample() {
        ChatFormat.Ctx ctx = new ChatFormat.Ctx();
        ctx.player = "Steve";
        ctx.world = "主世界";
        ctx.server = Config.serverDisplay;
        ctx.message = "hello world";
        ctx.keepClick = true;
        return ctx;
    }

    /** 渲染开销基准：分别测「布局已编译」和「每次重新编译」两条路径。 */
    private static void bench(ICommandSender sender, String countText) {
        int n;
        try {
            n = Math.max(1000, Math.min(200000, Integer.parseInt(countText.trim())));
        } catch (NumberFormatException e) {
            n = 20000;
        }
        String layout = Config.rules.isEmpty() ? "&6[&e{server}&6]{world}{player}&r: {message}" : Config.rules.get(Config.rules.size() - 1).layout;
        ChatFormat.Ctx ctx = sample();

        for (int i = 0; i < 2000; i++) ChatFormat.render(ctx, layout);

        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) ChatFormat.render(ctx, layout);
        long cached = System.nanoTime() - t0;

        for (int i = 0; i < 2000; i++) ChatFormat.renderUncached(ctx, layout);

        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) ChatFormat.renderUncached(ctx, layout);
        long raw = System.nanoTime() - t0;

        // 不带样式的布局：用来区分「颜色码解析」和「悬停/点击构造」各占多少
        String plainLayout = "&6[&e{server}&6]{world}{player}&r: {message}";
        for (int i = 0; i < 2000; i++) ChatFormat.render(ctx, plainLayout);
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) ChatFormat.render(ctx, plainLayout);
        long plain = System.nanoTime() - t0;

        reply(sender, String.format("渲染 %d 次：已编译布局 %.0f ns/次，每次重编译 %.0f ns/次，无悬停点击 %.0f ns/次", n,
            cached / (double) n, raw / (double) n, plain / (double) n));
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

    /** 把组件树展开为单行，用于在控制台核对渲染结果。 */
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
            return Arrays.asList("chat", "json", "bench", "achievement", "relay", "nei", "item");
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
