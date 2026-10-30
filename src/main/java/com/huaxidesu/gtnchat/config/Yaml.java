package com.huaxidesu.gtnchat.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只覆盖本模组配置文件用到的那一小部分 YAML 语法：嵌套映射、标量列表、单双引号字符串。
 *
 * <p>不支持的写法一律按字面量处理，不做兼容：锚点、别名、多文档、流式集合（{@code [a, b]}）、
 * 跨行字符串。配置由模组自己生成，使用者只会改动值和条目，这些语法用不到。
 * 解析结果统一为 {@code Map<String,Object>} / {@code List<String>} / {@code String}，
 * 映射保持文件里的书写顺序（{@link LinkedHashMap}），规则匹配依赖这个顺序。
 */
public final class Yaml {

    private Yaml() {}

    /** 一条有效配置行：缩进宽度 + 去掉注释后的正文。 */
    private static final class Line {

        final int indent;
        final String text;

        Line(int indent, String text) {
            this.indent = indent;
            this.text = text;
        }
    }

    public static Map<String, Object> parse(String content) {
        List<Line> lines = new ArrayList<Line>();
        String[] raw = content.split("\r?\n", -1);
        for (String s : raw) {
            String noComment = stripComment(s);
            if (noComment.trim().isEmpty()) continue;
            int indent = 0;
            while (indent < noComment.length() && noComment.charAt(indent) == ' ') {
                indent++;
            }
            lines.add(new Line(indent, noComment.substring(indent).trim()));
        }
        if (lines.isEmpty()) return new LinkedHashMap<String, Object>();
        int[] cursor = new int[] { 0 };
        Object root = parseBlock(lines, cursor, lines.get(0).indent);
        if (root instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) root;
            return map;
        }
        return new LinkedHashMap<String, Object>();
    }

    private static Object parseBlock(List<Line> lines, int[] cursor, int indent) {
        if (cursor[0] >= lines.size()) return "";
        if (lines.get(cursor[0]).text.startsWith("-")) return parseList(lines, cursor, indent);
        return parseMap(lines, cursor, indent);
    }

    private static Map<String, Object> parseMap(List<Line> lines, int[] cursor, int indent) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        while (cursor[0] < lines.size()) {
            Line line = lines.get(cursor[0]);
            if (line.indent < indent) break;
            if (line.indent > indent) {
                // 缩进跳级：忽略这一行，避免一处手误把整份配置带偏
                cursor[0]++;
                continue;
            }
            if (line.text.startsWith("-")) break;
            int colon = indexOfSeparator(line.text);
            if (colon < 0) {
                cursor[0]++;
                continue;
            }
            String key = unquote(line.text.substring(0, colon).trim());
            String rest = line.text.substring(colon + 1).trim();
            cursor[0]++;
            if (rest.isEmpty()) {
                if (cursor[0] < lines.size() && lines.get(cursor[0]).indent > indent) {
                    map.put(key, parseBlock(lines, cursor, lines.get(cursor[0]).indent));
                } else {
                    map.put(key, "");
                }
            } else {
                map.put(key, scalar(rest));
            }
        }
        return map;
    }

    private static List<String> parseList(List<Line> lines, int[] cursor, int indent) {
        List<String> list = new ArrayList<String>();
        while (cursor[0] < lines.size()) {
            Line line = lines.get(cursor[0]);
            if (line.indent < indent) break;
            if (line.indent > indent) {
                cursor[0]++;
                continue;
            }
            if (!line.text.startsWith("-")) break;
            String rest = line.text.substring(1).trim();
            cursor[0]++;
            list.add(scalar(rest));
        }
        return list;
    }

    /** 找键值分隔用的冒号：跳过引号内的冒号。 */
    private static int indexOfSeparator(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ':') {
                return i;
            }
        }
        return -1;
    }

    /** 去掉行尾注释；引号内的 {@code #} 不算注释。 */
    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String scalar(String text) {
        String v = text.trim();
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if (first == '\'' && last == '\'') {
                return v.substring(1, v.length() - 1).replace("''", "'");
            }
            if (first == '"' && last == '"') {
                return unescape(v.substring(1, v.length() - 1));
            }
        }
        return v;
    }

    private static String unquote(String text) {
        return scalar(text);
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n':
                    sb.append('\n');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                default:
                    sb.append('\\').append(n);
            }
        }
        return sb.toString();
    }
}
