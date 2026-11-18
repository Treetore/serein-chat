package com.serein.chat.api;

import java.util.ArrayList;
import java.util.List;

/** 与协议无关的请求描述，由各协议实现翻译成自己的 body。 */
public final class ChatRequest {

    public String model = "";
    public String system = "";
    public final List<Turn> turns = new ArrayList<>();
    public boolean thinking;
    /** 思考强度：low / high / max（null 或空表示不下发） */
    public String effort;
    /** 是否声明服务端联网搜索工具（仅 DeepSeek 官方支持） */
    public boolean search;
    /** 是否允许发送图片（由模型能力决定） */
    public boolean vision;
    /** 首轮请求顺带让服务端生成标题（仅 DeepSeek 官方） */
    public boolean withTitle;
    /** 命中本地前缀缓存时的历史 messages JSON（保证跨轮字节一致） */
    public String cachedPrefix;
}
