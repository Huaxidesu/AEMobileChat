package com.huaxidesu.gtnchat.config;

import java.util.Collections;
import java.util.List;

/** 一个聊天片段的显示样式。 */
public final class Style {

    public final String text;
    public final List<String> hover;
    /** {@code run} / {@code suggest} / {@code url}，为空表示没有点击动作。 */
    public final String clickAction;
    public final String clickValue;

    public Style(String text, List<String> hover, String clickAction, String clickValue) {
        this.text = text == null ? "" : text;
        this.hover = hover == null ? Collections.<String>emptyList() : hover;
        this.clickAction = clickAction == null ? "" : clickAction;
        this.clickValue = clickValue == null ? "" : clickValue;
    }
}
