package com.huaxidesu.gtnchat.chat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
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
 *
 * <p>布局在配置加载后编译成片段数组，规则按玩家缓存几秒：一条聊天的开销主要是字符串拼接，
 * 不随在线人数变化，也不做任何全服遍历。
 */
public final class ChatFormat {

    /** 任务分享标记。带这个标记的消息不截断，截断会让客户端的解析失效。 */
    public static final String QUEST_MARKER = "betterquesting.msg.sharequest:";

    private static final Rule FALLBACK = new Rule("fallback", "&6[&e{server}&6]{world}{player}&r: {message}", null, -1);

    private static final byte T_LITERAL = 0;
    private static final byte T_MESSAGE = 1;
    private static final byte T_ITEM = 2;
    private static final byte T_STYLE = 3;
    private static final byte T_BUILTIN = 4;

    /** 布局编译结果：一条聊天只要按顺序走一遍这个数组。 */
    private static final class Token {

        final byte kind;
        final String text;
        final Style style;

        Token(byte kind, String text, Style style) {
            this.kind = kind;
            this.text = text;
            this.style = style;
        }
    }

    private static final class Layout {

        static final Layout EMPTY = new Layout(new Token[0]);

        final Token[] tokens;

        Layout(Token[] tokens) {
            this.tokens = tokens;
        }
    }

    private static final Map<String, Layout> LAYOUTS = new ConcurrentHashMap<String, Layout>();

    private static final class Cached {

        final Rule rule;
        final long at;

        Cached(Rule rule, long at) {
            this.rule = rule;
            this.at = at;
        }
    }

    /** 规则缓存有效期。权限变更最多晚这么久生效，换来的是每条聊天省掉一次权限查询。 */
    private static final long RULE_TTL_MS = 5000L;

    private static final Map<UUID, Cached> RULE_CACHE = new ConcurrentHashMap<UUID, Cached>();

    private ChatFormat() {}

    /** 配置重载后清空派生数据。 */
    public static void invalidate() {
        LAYOUTS.clear();
        RULE_CACHE.clear();
    }

    /** 基准测试用：只清规则缓存，保留已编译的布局。 */
    public static void clearRuleCache() {
        RULE_CACHE.clear();
    }

    /** 渲染上下文。跨服消息没有在线玩家对象，只能用名字和世界名。 */
    public static final class Ctx {

        public String player = "";
        public String world = "";
        public String server = "";
        public String message = "";
        /** 玩家所在团队的名字；空串表示没有团队，{team} 会渲染成空 */
        public String team = "";
        /** 团队简介，只给悬停用 */
        public String teamDesc = "";
        /** 职位（队长 / 管理员 / 成员），只给悬停用 */
        public String teamRank = "";
        /** 队伍颜色码字符（{@code 9} / {@code b} …），给样式里的 {@code {teamcolor}} 用 */
        public String teamColor = TeamInfo.DEFAULT_COLOR;
        /** 是否保留点击动作。跨服消息默认不保留。 */
        public boolean keepClick = true;
        /** 只有 {@code {item}} 用得到，NEI 物品链接用组件而不是纯文本。 */
        public IChatComponent item;
    }

    // ------------------------------------------------------------------ 规则

    public static Rule pick(EntityPlayerMP player) {
        // 只有一条无条件的规则时不必查缓存，这是最常见的配置
        List<Rule> rules = Config.rules;
        if (rules.size() == 1) {
            Rule only = rules.get(0);
            if (only.op < 0 && only.permission == null) return only;
        }
        UUID id = player.getUniqueID();
        long now = System.currentTimeMillis();
        Cached cached = RULE_CACHE.get(id);
        if (cached != null && now - cached.at < RULE_TTL_MS) return cached.rule;
        Rule rule = match(player);
        RULE_CACHE.put(id, new Cached(rule, now));
        return rule;
    }

    /**
     * 是否管理员。
     *
     * <p>不能用 {@code player.canCommandSenderUseCommand(1, "gtchat")}：在 Thermos 上
     * 它对非 op 也返回 true（Bukkit 权限层把没注册的节点当默认允许），结果是所有人都
     * 匹配到 admin 规则、名字前多一个 [管理员]。这里直接查 op 列表。
     */
    private static boolean isOp(EntityPlayerMP player) {
        try {
            return MinecraftServer.getServer().getConfigurationManager()
                .func_152596_g(player.getGameProfile());
        } catch (Throwable t) {
            return player.canCommandSenderUseCommand(1, "gtchat");
        }
    }

    /** 不走缓存地匹配一次，基准测试用。 */
    public static Rule match(EntityPlayerMP player) {
        for (Rule rule : Config.rules) {
            if (rule.op >= 0 && !isOp(player)) continue;
            if (rule.permission != null && !hasPermission(player, rule.permission)) continue;
            return rule;
        }
        return FALLBACK;
    }

    // ------------------------------------------------------------------ 渲染

    public static IChatComponent render(Ctx ctx, Rule rule) {
        return render(ctx, rule == null ? FALLBACK.layout : rule.layout);
    }

    public static IChatComponent render(Ctx ctx, String layout) {
        Layout compiled = LAYOUTS.get(layout == null ? "" : layout);
        if (compiled == null) {
            compiled = compile(layout == null ? "" : layout);
            LAYOUTS.put(layout == null ? "" : layout, compiled);
        }
        ChatComponentText root = new ChatComponentText("");
        for (Token token : compiled.tokens) {
            switch (token.kind) {
                case T_LITERAL:
                    McText.append(root, token.text);
                    break;
                case T_MESSAGE:
                    McText.append(root, ctx.message);
                    break;
                case T_ITEM:
                    if (ctx.item != null) root.appendSibling(ctx.item);
                    break;
                case T_STYLE:
                    renderStyle(root, token.style, ctx);
                    break;
                default: {
                    String value = builtin(token.text, ctx);
                    McText.append(root, value == null ? "{" + token.text + "}" : value);
                }
            }
        }
        return root;
    }

    /** 基准测试用：跳过布局缓存，每次都重新编译。 */
    public static IChatComponent renderUncached(Ctx ctx, String layout) {
        String text = layout == null ? "" : layout;
        ChatComponentText root = new ChatComponentText("");
        for (Token token : compile(text).tokens) {
            switch (token.kind) {
                case T_LITERAL:
                    McText.append(root, token.text);
                    break;
                case T_MESSAGE:
                    McText.append(root, ctx.message);
                    break;
                case T_ITEM:
                    if (ctx.item != null) root.appendSibling(ctx.item);
                    break;
                case T_STYLE:
                    renderStyle(root, token.style, ctx);
                    break;
                default: {
                    String value = builtin(token.text, ctx);
                    McText.append(root, value == null ? "{" + token.text + "}" : value);
                }
            }
        }
        return root;
    }

    /** 把布局切成片段：字面量、占位符、样式引用。只在配置变化后做一次。 */
    private static Layout compile(String layout) {
        if (layout.isEmpty()) return Layout.EMPTY;
        List<Token> tokens = new ArrayList<Token>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < layout.length()) {
            int open = layout.indexOf('{', i);
            int close = open < 0 ? -1 : layout.indexOf('}', open);
            if (open < 0 || close < 0) {
                literal.append(layout, i, layout.length());
                break;
            }
            if (open > i) literal.append(layout, i, open);
            String key = layout.substring(open + 1, close);
            if ("message".equals(key) || "item".equals(key) || Config.styles.containsKey(key) || isBuiltin(key)) {
                flushLiteral(tokens, literal);
                if ("message".equals(key)) {
                    tokens.add(new Token(T_MESSAGE, null, null));
                } else if ("item".equals(key)) {
                    tokens.add(new Token(T_ITEM, null, null));
                } else if (Config.styles.containsKey(key)) {
                    tokens.add(new Token(T_STYLE, null, Config.styles.get(key)));
                } else {
                    tokens.add(new Token(T_BUILTIN, key, null));
                }
            } else {
                literal.append('{').append(key).append('}');
            }
            i = close + 1;
        }
        flushLiteral(tokens, literal);
        return new Layout(tokens.toArray(new Token[tokens.size()]));
    }

    private static void flushLiteral(List<Token> tokens, StringBuilder literal) {
        if (literal.length() == 0) return;
        tokens.add(new Token(T_LITERAL, literal.toString(), null));
        literal.setLength(0);
    }

    private static void renderStyle(IChatComponent parent, Style style, Ctx ctx) {
        // 片段里的占位符全空时（比如没有队伍的 {team}），不要留下一个空括号
        String text = expand(style.text, ctx);
        if (isEffectivelyEmpty(text)) return;
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
        McText.append(box, text);
        parent.appendSibling(box);
    }

    /** 去掉颜色码和括号后还剩不剩东西 */
    private static boolean isEffectivelyEmpty(String text) {
        if (text == null || text.isEmpty()) return true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' || c == '&') { i++; continue; }
            if (c == '[' || c == ']' || c == ' ' || c == ':' || c == '<' || c == '>') continue;
            return false;
        }
        return true;
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

    private static boolean isBuiltin(String key) {
        return "server".equals(key) || "world".equals(key) || "player".equals(key)
                || "team".equals(key) || "teamdesc".equals(key) || "teamrank".equals(key)
                || "teamcolor".equals(key);
    }

    private static String builtin(String key, Ctx ctx) {
        if ("server".equals(key)) return clean(ctx.server);
        if ("world".equals(key)) return clean(ctx.world);
        if ("player".equals(key)) return clean(ctx.player);
        if ("team".equals(key)) return clean(ctx.team);
        if ("teamdesc".equals(key)) return clean(ctx.teamDesc);
        if ("teamrank".equals(key)) return clean(ctx.teamRank);
        // 队伍颜色：ServerUtilities 里配的那个色，渲染成 &9 这种形式交给后面的颜色解析。
        // 没有队伍时给默认色，免得样式里的 &{teamcolor} 变成字面量。
        if ("teamcolor".equals(key)) {
            String c = ctx.teamColor == null || ctx.teamColor.isEmpty() ? TeamInfo.DEFAULT_COLOR : ctx.teamColor;
            return "&" + c;
        }
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

    /** 世界显示名：MV alias > config 覆盖 > 内置维度表 > 家园世界 > provider 名 */
    public static String worldName(EntityPlayerMP player) {
        return WorldNames.of(player);
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
