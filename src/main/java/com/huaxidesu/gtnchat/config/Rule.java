package com.huaxidesu.gtnchat.config;

/**
 * 一条聊天规则：满足条件时用 {@link #layout} 渲染。
 *
 * <p>条件为空表示无条件匹配。多个条件之间是「与」关系。
 */
public final class Rule {

    public final String name;
    public final String layout;
    /** 权限节点，可能为 null。 */
    public final String permission;
    /** 需要的 op 等级，-1 表示不限制。 */
    public final int op;

    public Rule(String name, String layout, String permission, int op) {
        this.name = name;
        this.layout = layout;
        this.permission = permission;
        this.op = op;
    }
}
