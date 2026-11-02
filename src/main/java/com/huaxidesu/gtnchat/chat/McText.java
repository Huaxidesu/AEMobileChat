package com.huaxidesu.gtnchat.chat;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

/**
 * 把带 {@code &} / {@code §} 颜色码的字符串转成聊天组件。
 *
 * <p>不解析 {@code jsonToComponent}，也不接受任何 JSON 输入：颜色码是唯一被识别的控制语法，
 * 其余字符（包括引号、反斜杠、花括号）一律当普通文本，天然不会被拼成额外的组件结构。
 */
public final class McText {

    private static final char[] CODES = "0123456789abcdefklmnor".toCharArray();
    private static final EnumChatFormatting[] FORMATS = new EnumChatFormatting[CODES.length];

    static {
        for (EnumChatFormatting f : EnumChatFormatting.values()) {
            String s = f.toString();
            if (s.length() != 2) continue;
            char c = Character.toLowerCase(s.charAt(1));
            for (int i = 0; i < CODES.length; i++) {
                if (CODES[i] == c) {
                    FORMATS[i] = f;
                    break;
                }
            }
        }
    }

    private McText() {}

    /** 追加到父组件；颜色码切换时切成新的子组件。 */
    public static void append(IChatComponent parent, String text) {
        if (text == null || text.isEmpty()) return;
        ChatStyle current = new ChatStyle();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '&' || c == '\u00a7') && i + 1 < text.length()) {
                EnumChatFormatting fmt = lookup(text.charAt(i + 1));
                if (fmt != null) {
                    flush(parent, buf, current);
                    current = apply(current, fmt, text.charAt(i + 1));
                    i++;
                    continue;
                }
            }
            buf.append(c);
        }
        flush(parent, buf, current);
    }

    /** 包一层容器，方便给整段设置悬停或点击。 */
    public static IChatComponent of(String text) {
        ChatComponentText root = new ChatComponentText("");
        append(root, text);
        return root;
    }

    /** 这个字符是不是颜色码字符（不含前导的 {@code &} / {@code §}）。 */
    public static boolean isCode(char c) {
        return lookup(c) != null;
    }

    /** 去掉颜色码，用于日志和长度判断。 */
    public static String plain(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '&' || c == '\u00a7') && i + 1 < text.length() && lookup(text.charAt(i + 1)) != null) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static EnumChatFormatting lookup(char c) {
        char lower = Character.toLowerCase(c);
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i] == lower) return FORMATS[i];
        }
        return null;
    }

    private static void flush(IChatComponent parent, StringBuilder buf, ChatStyle style) {
        if (buf.length() == 0) return;
        ChatComponentText part = new ChatComponentText(buf.toString());
        part.setChatStyle(copy(style));
        parent.appendSibling(part);
        buf.setLength(0);
    }

    private static ChatStyle apply(ChatStyle base, EnumChatFormatting fmt, char code) {
        ChatStyle style = copy(base);
        char c = Character.toLowerCase(code);
        if (c == 'r') {
            return new ChatStyle();
        }
        if (c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
            // 颜色码会清掉之前设置的加粗、斜体等格式，与原版行为一致
            style = new ChatStyle();
            style.setColor(fmt);
            return style;
        }
        switch (c) {
            case 'k':
                style.setObfuscated(Boolean.TRUE);
                break;
            case 'l':
                style.setBold(Boolean.TRUE);
                break;
            case 'm':
                style.setStrikethrough(Boolean.TRUE);
                break;
            case 'n':
                style.setUnderlined(Boolean.TRUE);
                break;
            case 'o':
                style.setItalic(Boolean.TRUE);
                break;
            default:
                break;
        }
        return style;
    }

    private static ChatStyle copy(ChatStyle s) {
        ChatStyle c = new ChatStyle();
        c.setColor(s.getColor());
        c.setBold(s.getBold());
        c.setItalic(s.getItalic());
        c.setUnderlined(s.getUnderlined());
        c.setStrikethrough(s.getStrikethrough());
        c.setObfuscated(s.getObfuscated());
        return c;
    }
}
