package com.serein.chat.api;

import java.util.ArrayDeque;
import java.util.List;

/**
 * SSE（text/event-stream）增量解码器，按 WHATWG 规范实现：
 * 字节流 → UTF-8 解码 → 按 CRLF / LF / CR 切行 → 字段累积 → 空行派发事件。
 * 注释行（服务端保活 `: keep-alive`）忽略。对应 chat-demo 的 services/api/sse.ts。
 */
public final class Sse {

    public static final class Event {
        public final String name;
        public final String data;

        Event(String name, String data) {
            this.name = name;
            this.data = data;
        }
    }

    private final StringBuilder buffer = new StringBuilder();
    private final StringBuilder data = new StringBuilder();
    private String eventName = "";
    private boolean hasData;
    private final ArrayDeque<Event> out = new ArrayDeque<>();

    /** 送入一段已解码文本，返回其中完整的事件（按出现顺序）。 */
    public List<Event> push(String text) {
        out.clear();
        buffer.append(text);
        int lineStart = 0;
        int i = 0;
        int n = buffer.length();
        while (i < n) {
            char c = buffer.charAt(i);
            int lineEnd;
            int next;
            if (c == '\r') {
                if (i + 1 == n) break; // 结尾 CR 可能是 CRLF 前半，等下一段
                lineEnd = i;
                next = (buffer.charAt(i + 1) == '\n') ? i + 2 : i + 1;
            } else if (c == '\n') {
                lineEnd = i;
                next = i + 1;
            } else {
                i++;
                continue;
            }
            processLine(buffer.substring(lineStart, lineEnd));
            lineStart = next;
            i = next;
        }
        if (lineStart > 0) buffer.delete(0, lineStart);
        return out.isEmpty() ? java.util.Collections.<Event>emptyList() : new java.util.ArrayList<>(out);
    }

    /** 流结束：结尾 CR 视为完整换行，未以空行结尾的事件按规范丢弃。 */
    public List<Event> end() {
        out.clear();
        if (buffer.length() > 0 && buffer.charAt(buffer.length() - 1) == '\r') {
            processLine(buffer.substring(0, buffer.length() - 1));
        }
        buffer.setLength(0);
        data.setLength(0);
        hasData = false;
        eventName = "";
        return out.isEmpty() ? java.util.Collections.<Event>emptyList() : new java.util.ArrayList<>(out);
    }

    private void processLine(String line) {
        if (line.isEmpty()) {
            dispatch();
            return;
        }
        if (line.charAt(0) == ':') return;
        int colon = line.indexOf(':');
        String field = colon == -1 ? line : line.substring(0, colon);
        String value = colon == -1 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        if ("data".equals(field)) {
            if (hasData) data.append('\n');
            data.append(value);
            hasData = true;
        } else if ("event".equals(field)) {
            eventName = value;
        }
        // id / retry 不参与消费
    }

    private void dispatch() {
        if (hasData) {
            out.add(new Event(eventName.isEmpty() ? "message" : eventName, data.toString()));
        }
        data.setLength(0);
        hasData = false;
        eventName = "";
    }
}
