package com.huaxidesu.gtnchat.config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.huaxidesu.gtnchat.GTNHChat;

/**
 * 配置加载。两份文件都在 {@code config/gtnchat/} 下，缺文件时写一份带注释的默认值出来。
 *
 * <p>重载整体替换，不做增量更新：规则表和样式表在渲染时被整表读取，
 * 逐条打补丁只会让「文件里写的」和「内存里跑的」不一致。
 */
public final class Config {

    public static File dir;

    public static boolean debug = false;

    public static String serverId = "server1";
    public static String serverDisplay = "服务器";

    public static int maxLength = 256;
    /** 名字留边口径，见 {@link com.huaxidesu.gtnchat.chat.ChatHeads} */
    public static final int CHAT_HEADS_AUTO = 0;
    public static final int CHAT_HEADS_ON = 1;
    public static final int CHAT_HEADS_OFF = 2;
    public static int chatHeads = CHAT_HEADS_AUTO;
    public static final List<Rule> rules = new ArrayList<Rule>();
    public static final Map<String, Style> styles = new LinkedHashMap<String, Style>();
    public static final Map<Integer, String> worldNames = new LinkedHashMap<Integer, String>();

    public static boolean relayEnabled = true;
    public static String relayChannel = "gtnchat";
    public static int relayProtocol = 1;
    public static boolean relayKeepClick = false;
    /** 跨服消息是否额外写一条带来源的日志（控制台翻日志时好找） */
    public static boolean relayLog = true;

    public static boolean achievementEnabled = true;
    public static String achievementLayout = "&e{player} &7达成了成就 &b{achievement}";

    public static boolean neiEnabled = true;
    public static String neiLayout = "&e{player} &7分享了 {item}";

    /** 最近一次加载失败的原因，给 {@code /gtchat reload} 回显用。 */
    public static String lastError = null;

    private Config() {}

    public static void init(File configDir) {
        dir = configDir;
        if (!dir.exists() && !dir.mkdirs()) {
            GTNHChat.LOG.warn("无法创建配置目录: " + dir.getAbsolutePath());
        }
        ensure("config.yml");
        ensure("style.yml");
        reload();
    }

    private static void ensure(String name) {
        File target = new File(dir, name);
        if (target.exists()) return;
        InputStream in = Config.class.getResourceAsStream("/defaults/" + name);
        if (in == null) {
            GTNHChat.LOG.warn("默认配置资源缺失: " + name);
            return;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            FileOutputStream fos = new FileOutputStream(target);
            try {
                fos.write(out.toByteArray());
            } finally {
                fos.close();
            }
        } catch (Exception e) {
            GTNHChat.LOG.warn("写入默认配置失败: " + name, e);
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {}
        }
    }

    public static synchronized void reload() {
        lastError = null;
        // 布局与规则缓存是从配置派生的，重载后必须作废
        com.huaxidesu.gtnchat.chat.ChatFormat.invalidate();
        try {
            Map<String, Object> root = Yaml.parse(read("config.yml"));
            Map<String, Object> server = map(root.get("server"));
            serverId = str(server.get("id"), serverId);
            serverDisplay = str(server.get("display"), serverDisplay);

            Map<String, Object> chat = map(root.get("chat"));
            maxLength = num(chat.get("max-length"), maxLength);
            chatHeads = headsMode(chat.get("chat-heads"), chatHeads);
            parseRules(map(chat.get("rules")));

            worldNames.clear();
            for (Map.Entry<String, Object> e : map(root.get("world-names")).entrySet()) {
                try {
                    worldNames.put(Integer.valueOf(e.getKey().trim()), str(e.getValue(), e.getKey()));
                } catch (NumberFormatException ignored) {}
            }

            Map<String, Object> relay = map(root.get("relay"));
            relayEnabled = bool(relay.get("enabled"), relayEnabled);
            relayChannel = str(relay.get("channel"), relayChannel);
            relayProtocol = num(relay.get("protocol"), relayProtocol);
            relayKeepClick = bool(relay.get("keep-click"), relayKeepClick);
            relayLog = bool(relay.get("log"), relayLog);

            Map<String, Object> ach = map(root.get("achievements"));
            achievementEnabled = bool(ach.get("enabled"), achievementEnabled);
            achievementLayout = str(ach.get("layout"), achievementLayout);

            Map<String, Object> nei = map(root.get("nei"));
            neiEnabled = bool(nei.get("enabled"), neiEnabled);
            neiLayout = str(nei.get("layout"), neiLayout);

            debug = bool(root.get("debug"), debug);

            parseStyles(Yaml.parse(read("style.yml")));

            if (rules.isEmpty()) {
                rules.add(new Rule("default", "&6[&e{server}&6]{world}{player}&r: {message}", null, -1));
            }
        } catch (Exception e) {
            lastError = e.toString();
            GTNHChat.LOG.warn("配置加载失败，沿用上一次的值", e);
        }
    }

    private static void parseRules(Map<String, Object> section) {
        rules.clear();
        for (Map.Entry<String, Object> e : section.entrySet()) {
            Map<String, Object> body = map(e.getValue());
            String layout = str(body.get("layout"), null);
            if (layout == null || layout.isEmpty()) {
                GTNHChat.LOG.warn("规则 " + e.getKey() + " 没有 layout，已跳过");
                continue;
            }
            String permission = str(body.get("permission"), null);
            if (permission != null && permission.isEmpty()) permission = null;
            int op = body.containsKey("op") ? num(body.get("op"), -1) : -1;
            rules.add(new Rule(e.getKey(), layout, permission, op));
        }
    }

    private static void parseStyles(Map<String, Object> root) {
        styles.clear();
        for (Map.Entry<String, Object> e : root.entrySet()) {
            Map<String, Object> body = map(e.getValue());
            String text = str(body.get("text"), "");
            List<String> hover = new ArrayList<String>();
            Object rawHover = body.get("hover");
            if (rawHover instanceof List) {
                for (Object o : (List<?>) rawHover) hover.add(String.valueOf(o));
            } else if (rawHover instanceof String && !((String) rawHover).isEmpty()) {
                hover.add((String) rawHover);
            }
            Map<String, Object> click = map(body.get("click"));
            styles.put(e.getKey(), new Style(text, hover, str(click.get("action"), ""), str(click.get("value"), "")));
        }
    }

    private static String read(String name) throws Exception {
        File f = new File(dir, name);
        if (!f.exists()) return "";
        byte[] data = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
        } finally {
            in.close();
        }
        return new String(data, "UTF-8");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        return new LinkedHashMap<String, Object>();
    }

    private static String str(Object o, String def) {
        if (o == null) return def;
        return String.valueOf(o);
    }

    private static int num(Object o, int def) {
        if (o == null) return def;
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean bool(Object o, boolean def) {
        if (o == null) return def;
        String s = String.valueOf(o).trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        return def;
    }

    /** {@code chat-heads}：auto / on / off，也接受 true / false */
    private static int headsMode(Object o, int def) {
        if (o == null) return def;
        String s = String.valueOf(o).trim();
        if ("on".equalsIgnoreCase(s) || "true".equalsIgnoreCase(s)) return CHAT_HEADS_ON;
        if ("off".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) return CHAT_HEADS_OFF;
        if ("auto".equalsIgnoreCase(s)) return CHAT_HEADS_AUTO;
        return def;
    }
}
