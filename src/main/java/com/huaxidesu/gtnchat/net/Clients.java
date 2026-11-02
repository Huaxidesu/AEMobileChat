package com.huaxidesu.gtnchat.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.huaxidesu.gtnchat.config.Config;

/**
 * 「哪些玩家的客户端装了本模组、它们各自能做什么」。
 *
 * <p>握手是单向的一次性事件：服务端进服时发 {@code TYPE_HELLO}，客户端收到后回一帧
 * {@code TYPE_CLIENT_READY}，里面带<b>它自己的协议版本</b>和<b>能力位</b>。
 * 服务端把这些记下来，用来决定：
 * <ul>
 *   <li>成就公告由谁发 —— 客户端装了本模组就该由客户端上报驱动；</li>
 *   <li>聊天要不要给名字留边 —— 看有没有人报「会画头像」；</li>
 *   <li>{@code /gtchat status} 里那行「上报 N 台」列的是什么。</li>
 * </ul>
 *
 * <p>只记录<b>这一世</b>的状态：玩家退出就删。离线模式同名换人时不能沿用上一个人的能力。
 * 断开不清会留下幽灵，清了又会让「老版本从没回执过」和「刚退出的玩家」混为一谈，
 * 所以退出一律删，查询一律实时。
 */
public final class Clients {

    private static final Map<String, Entry> MAP = new ConcurrentHashMap<String, Entry>();

    private Clients() {}

    /** 一台客户端上报的内容 */
    public static final class Entry {

        /** 客户端自己的协议版本，可能比本服低 */
        public final int protocol;
        /** 客户端能力位，见 {@link Caps#C_CHAT_HEADS} */
        public final int caps;
        public final long time;

        Entry(int protocol, int caps, long time) {
            this.protocol = protocol;
            this.caps = caps;
            this.time = time;
        }
    }

    public static void put(String player, int protocol, int caps) {
        if (player == null || player.isEmpty()) return;
        MAP.put(player, new Entry(protocol, caps, System.currentTimeMillis()));
    }

    public static void remove(String player) {
        if (player == null) return;
        MAP.remove(player);
    }

    public static void clearAll() {
        MAP.clear();
    }

    public static boolean present(String player) {
        return player != null && MAP.containsKey(player);
    }

    public static int count() {
        return MAP.size();
    }

    /**
     * {@code /gtchat status} 用：上报了几台、都是谁、各自什么版本什么能力。
     *
     * <p>只列前 3 个人，人多时后面写「等」—— 这行是给人一眼看状态的，不是清单。
     */
    public static String summary() {
        if (MAP.isEmpty()) return "0 台（没有客户端回执，成就公告回退为由服务端按事件播）";
        List<String> names = new ArrayList<String>(MAP.keySet());
        Collections.sort(names);
        StringBuilder sb = new StringBuilder();
        sb.append(MAP.size()).append(" 台：");
        int shown = 0;
        for (String name : names) {
            if (shown == 3) {
                sb.append(" 等");
                break;
            }
            if (shown > 0) sb.append('，');
            Entry e = MAP.get(name);
            sb.append(name)
                .append("(协议")
                .append(e == null ? Config.relayMinProtocol : e.protocol)
                .append(' ')
                .append(Caps.describeClient(e == null ? 0 : e.caps))
                .append(')');
            shown++;
        }
        return sb.toString();
    }
}
