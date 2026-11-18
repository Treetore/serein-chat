package com.serein.chat.api;

import com.serein.chat.data.Models;

/** 一轮上下文：用户或助手。 */
public final class Turn {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public String role;
    public String text = "";
    /** 用户轮次的附件（图片按能力决定是否内联） */
    public java.util.List<Models.FileRef> files = new java.util.ArrayList<>();
    /** assistant 轮的协议原生内容：Anthropic 为块数组 JSON，OpenAI 为消息对象 JSON；非空时原样回传 */
    public String rawJson = "";

    public Turn(String role, String text) {
        this.role = role;
        this.text = text == null ? "" : text;
    }
}
