package com.huaxidesu.gtnchat.chat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;

import com.huaxidesu.gtnchat.GTNHChat;
import com.huaxidesu.gtnchat.config.Config;

/**
 * 世界（维度）显示名解析。
 *
 * <p>名字按下面的顺序取第一个命中的：
 * <ol>
 *   <li><b>Multiverse 的 alias</b>（{@code plugins/Multiverse-Core/worlds.yml}）——
 *       服主自己起的中文名，最权威；alias 和世界文件夹名一样时视为未设置；</li>
 *   <li><b>config 覆盖</b>（{@code world-names} 里的 {@code '70': 暮色森林}）；</li>
 *   <li><b>内置维度表</b>（按 dimid，覆盖原版 + GTNH 常用维度）；</li>
 *   <li>家园世界缺省显示为 {@code 家园 #180}；</li>
 *   <li>再回退到 provider 英文名，最后是世界文件夹名。</li>
 * </ol>
 *
 * <p>和 AEMobile 的 {@code metrics/WorldNames} 是同一套规则：两个模组各自实现一份，
 * 避免互相依赖；改规则时两边一起改。
 *
 * <p>线程：{@link #reloadIfChanged()} 在主线程每 tick 调一次（只 stat mtime），
 * {@link #display} 只读内存，网络线程渲染聊天时可以直接用。
 */
public final class WorldNames {

    private static final String MV_RELATIVE = "plugins/Multiverse-Core/worlds.yml";
    /** PERSONAL_DIM_<n>：GTNH personalspace 的个人花园世界 */
    private static final String PERSONAL_PREFIX = "PERSONAL_DIM";

    /**
     * 世界文件夹名 → MV alias。
     *
     * <p>整体替换而不是原地 clear+putAll：这张表在主线程重建、在网络线程渲染聊天时读，
     * 原地改会读到「清空了一半」的中间状态。volatile 替换保证读到的永远是完整的一份。
     */
    private static volatile Map<String, String> ALIAS = Collections.<String, String>emptyMap();

    /** 按 dimid 的内置中文名 */
    private static final Map<Integer, String> BY_DIM = new HashMap<Integer, String>();

    private static File aliasFile;
    private static long aliasMtime = -1L;

    static {
        BY_DIM.put(-1, "下界");
        BY_DIM.put(0, "主世界");
        BY_DIM.put(1, "末地");
        BY_DIM.put(7, "暮色森林");
        BY_DIM.put(-100, "深空");
        BY_DIM.put(-112, "深渊");
    }

    private WorldNames() {}

    /** 服务端启动时定位 MV 配置文件 */
    public static void init(File serverRoot) {
        try {
            aliasFile = serverRoot == null ? null : new File(serverRoot, MV_RELATIVE);
        } catch (Throwable t) {
            aliasFile = null;
        }
        aliasMtime = -1L;
    }

    /** 玩家当前世界的显示名；任何一层命中就返回，不会返回 null */
    public static String of(EntityPlayerMP player) {
        if (player == null) return "";
        try {
            int dim = player.worldObj.provider.dimensionId;
            String folder = player.worldObj.provider.getSaveFolder();
            if (folder == null || folder.isEmpty()) {
                folder = player.worldObj.getWorldInfo().getWorldName();
            }
            String provider = player.worldObj.provider.getDimensionName();
            return display(folder, dim, provider);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 解析显示名；任何一层命中就返回，不会返回 null */
    public static String display(String worldName, int dim, String provider) {
        String raw = worldName == null ? "" : worldName;

        String alias = ALIAS.get(raw);
        if (alias != null && !alias.isEmpty() && !alias.equals(raw)) return alias;

        String cfg = Config.worldNames.get(dim);
        if (cfg != null && !cfg.isEmpty()) return cfg;

        String byDim = BY_DIM.get(dim);
        if (byDim != null) return byDim;

        if (isPersonal(raw)) {
            int cut = raw.lastIndexOf('_');
            String num = cut >= 0 && cut + 1 < raw.length() ? raw.substring(cut + 1) : "";
            return num.isEmpty() ? "家园世界" : "家园 #" + num;
        }

        if (provider != null && !provider.isEmpty() && !provider.equals(raw)) return provider;
        return raw.isEmpty() ? ("DIM" + dim) : raw;
    }

    public static boolean isPersonal(String worldName) {
        return worldName != null && worldName.startsWith(PERSONAL_PREFIX);
    }

    /** 文件变了才重新解析（每 tick stat 一次，成本可忽略） */
    public static void reloadIfChanged() {
        File f = aliasFile;
        if (f == null || !f.isFile()) return;
        long mtime = f.lastModified();
        if (mtime == aliasMtime) return;
        aliasMtime = mtime;
        try {
            Map<String, String> parsed = parse(f);
            ALIAS = Collections.unmodifiableMap(parsed);
            if (Config.debug) GTNHChat.LOG.info("Multiverse 世界别名: " + parsed.size() + " 条");
        } catch (Throwable t) {
            GTNHChat.LOG.warn("读取 Multiverse 世界别名失败", t);
        }
    }

    public static int aliasCount() {
        return ALIAS.size();
    }

    /**
     * 极简 YAML 解析：只认 {@code worlds:} 下面 2 空格缩进的 {@code <世界名>:} 和
     * 4 空格缩进的 {@code alias:}。MV 写的格式固定，不需要通用 YAML 解析器。
     */
    private static Map<String, String> parse(File file) throws Exception {
        Map<String, String> out = new LinkedHashMap<String, String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        try {
            boolean inWorlds = false;
            String current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                if (!inWorlds) {
                    if (line.trim().equals("worlds:")) inWorlds = true;
                    continue;
                }
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                String body = line.trim();
                if (indent == 0) {
                    inWorlds = false;
                    current = null;
                    continue;
                }
                if (indent == 2 && body.endsWith(":")) {
                    current = body.substring(0, body.length() - 1).trim();
                    continue;
                }
                if (current != null && indent == 4 && body.startsWith("alias:")) {
                    String value = unquote(body.substring(6).trim());
                    if (!value.isEmpty()) out.put(current, value);
                }
            }
        } finally {
            reader.close();
        }
        return out;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '\'' && b == '\'') || (a == '"' && b == '"')) s = s.substring(1, s.length() - 1);
        }
        return unescape(s);
    }

    /**
     * 还原 YAML 双引号里的转义。
     *
     * <p>MV 用 SnakeYAML 写文件，中文默认写成 {@code "\u534E\u6EAA\u5C9B"} 这种转义形式；
     * 只去引号的话拿到的是字面量 {@code \u534E}，别名永远匹配不上真实世界名 ——
     * 表现就是「服主改了世界名，聊天里还是『家园 #180』」。
     */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(i + 1);
            if (n == 'u' && i + 5 < s.length()) {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    i += 5;
                    continue;
                } catch (NumberFormatException ignored) {
                    // 不是合法转义，原样保留
                }
            } else if (n == '\\' || n == '"' || n == '\'') {
                sb.append(n);
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 供调试用：当前世界名的小写化比对（保留给以后加英文表） */
    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
