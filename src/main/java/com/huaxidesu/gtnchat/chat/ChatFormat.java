package com.huaxidesu.gtnchat.chat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

import com.huaxidesu.gtnchat.config.Config;
import com.huaxidesu.gtnchat.config.Rule;
import com.huaxidesu.gtnchat.config.Style;

/**
 * 把一条聊天渲染成聊天组件。
 *
 * <p>渲染全程只做字符串拼接和组件组装，不访问世界、不发包、不碰命令分发器，
 * 因此可以在网络线程里直接调用，回到主线程后只需要一次广播。
 */
public final class ChatFormat {

    /** 任务分享标记。带这个标记的消息不截断，截断会让客户端的解析失效。 */
    public static final String QUEST_MARKER = "betterquesting.msg.sharequest:";

    private static final Rule FALLBACK = new Rule("fallback", "&6[&e{server}&6]{world}{player}&r: {message}", null, -1);

    private ChatFormat() {}

    /** 渲染上下文。跨服消息没有在线玩家对象，只能用名字和世界名。 */
    public static final class Ctx {

        public String player = "";
        public String world = "";
        public String server = "";
        public String message = "";
        /** 是否保留点击动作。跨服消息默认不保留。 */
        public boolean keepClick = true;
        /** 只有 {@code {item}} 用得到，NEI 物品链接用组件而不是纯文本。 */
        public IChatComponent item;
    }

    public static Rule pick(EntityPlayerMP player) {
        for (Rule rule : Config.rules) {
            if (rule.op >= 0 && !player.canCommandSenderUseCommand(rule.op, "gtchat")) continue;
            if (rule.permission != null && !hasPermission(player, rule.permission)) continue;
            return rule;
        }
        return FALLBACK;
    }

    public static IChatComponent render(Ctx ctx, Rule rule) {
        return render(ctx, rule == null ? FALLBACK.layout : rule.layout);
    }

    public static IChatComponent render(Ctx ctx, String layout) {
        ChatComponentText root = new ChatComponentText("");
        String text = layout == null ? "" : layout;
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf('{', i);
            if (open < 0) {
                McText.append(root, text.substring(i));
                break;
            }
            int close = text.indexOf('}', open);
            if (close < 0) {
                McText.append(root, text.substring(i));
                break;
            }
            if (open > i) McText.append(root, text.substring(i, open));
            String key = text.substring(open + 1, close);
            if ("message".equals(key)) {
                McText.append(root, ctx.message);
            } else if ("item".equals(key) && ctx.item != null) {
                root.appendSibling(ctx.item);
            } else {
                Style style = Config.styles.get(key);
                if (style != null) {
                    renderStyle(root, style, ctx);
                } else {
                    String value = builtin(key, ctx);
                    if (value == null) McText.append(root, "{" + key + "}");
                    else McText.append(root, value);
                }
            }
            i = close + 1;
        }
        return root;
    }

    private static void renderStyle(IChatComponent parent, Style style, Ctx ctx) {
        ChatComponentText box = new ChatComponentText("");
        ChatStyle cs = new ChatStyle();
        if (!style.hover.isEmpty()) {
            cs.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover(style.hover, ctx)));
        }
        if (!style.clickAction.isEmpty() && ctx.keepClick) {
            ClickEvent.Action action = clickAction(style.clickAction);
            String value = expand(style.clickValue, ctx);
            if (action != null && !value.isEmpty()) cs.setChatClickEvent(new ClickEvent(action, value));
        }
        box.setChatStyle(cs);
        McText.append(box, expand(style.text, ctx));
        parent.appendSibling(box);
    }

    private static IChatComponent hover(List<String> lines, Ctx ctx) {
        ChatComponentText root = new ChatComponentText("");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) root.appendSibling(new ChatComponentText("\n"));
            McText.append(root, expand(lines.get(i), ctx));
        }
        return root;
    }

    private static ClickEvent.Action clickAction(String name) {
        String n = name.trim().toLowerCase();
        if ("run".equals(n) || "command".equals(n)) return ClickEvent.Action.RUN_COMMAND;
        if ("suggest".equals(n)) return ClickEvent.Action.SUGGEST_COMMAND;
        if ("url".equals(n) || "open_url".equals(n)) return ClickEvent.Action.OPEN_URL;
        return null;
    }

    /** 片段内部只展开内置占位符；不再递归查样式表，避免配置写错时无限展开。 */
    private static String expand(String text, Ctx ctx) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf('{', i);
            if (open < 0) {
                sb.append(text, i, text.length());
                break;
            }
            int close = text.indexOf('}', open);
            if (close < 0) {
                sb.append(text, i, text.length());
                break;
            }
            sb.append(text, i, open);
            String value = builtin(text.substring(open + 1, close), ctx);
            if (value == null) sb.append(text, open, close + 1);
            else sb.append(value);
            i = close + 1;
        }
        return sb.toString();
    }

    private static String builtin(String key, Ctx ctx) {
        if ("server".equals(key)) return clean(ctx.server);
        if ("world".equals(key)) return clean(ctx.world);
        if ("player".equals(key)) return clean(ctx.player);
        return null;
    }

    /** 名字里出现颜色码字符会打断整行的着色，这里直接去掉。 */
    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("&", "").replace("\u00a7", "");
    }

    /**
     * 清洗玩家输入：去掉控制字符，按配置截断。
     *
     * <p>带任务分享标记的消息不截断 —— 客户端靠固定长度的标记定位任务，截断后整条链接失效。
     */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else if (c < 0x20 || c == 0x7f) {
                continue;
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString();
        if (s.contains(QUEST_MARKER)) return s;
        int max = Config.maxLength;
        if (max > 0 && s.length() > max) {
            s = s.substring(0, max);
            // 不要以孤立的颜色码字符结尾
            if (s.endsWith("&") || s.endsWith("\u00a7")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static String worldName(EntityPlayerMP player) {
        try {
            int dim = player.worldObj.provider.dimensionId;
            String alias = Config.worldNames.get(dim);
            if (alias != null && !alias.isEmpty()) return alias;
            String name = player.worldObj.provider.getDimensionName();
            return name == null || name.isEmpty() ? String.valueOf(dim) : name;
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 权限

    private static Method bukkitGetPlayer;
    private static Method bukkitHasPermission;
    private static boolean bukkitChecked;

    /**
     * 权限判断。装了权限插件（服务端是 Cauldron 一类的混合端）时走 Bukkit 权限，
     * 否则退到 op 等级 1 —— 纯 Forge 端没有权限系统，只能这样分级。
     */
    private static boolean hasPermission(EntityPlayerMP player, String node) {
        if (!bukkitChecked) {
            bukkitChecked = true;
            try {
                Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
                bukkitGetPlayer = bukkit.getMethod("getPlayer", UUID.class);
                Class<?> playerClass = Class.forName("org.bukkit.entity.Player");
                bukkitHasPermission = playerClass.getMethod("hasPermission", String.class);
            } catch (Throwable ignored) {
                bukkitGetPlayer = null;
                bukkitHasPermission = null;
            }
        }
        if (bukkitGetPlayer != null && bukkitHasPermission != null) {
            try {
                Object p = bukkitGetPlayer.invoke(null, player.getUniqueID());
                if (p != null) return Boolean.TRUE.equals(bukkitHasPermission.invoke(p, node));
            } catch (Throwable ignored) {}
        }
        return player.canCommandSenderUseCommand(1, "gtchat");
    }
}
