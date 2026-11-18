package com.serein.chat.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 会话、消息与内容块模型。序列化用短键名，直接存进 SQLite。 */
public final class Models {

    public static final int ROLE_USER = 0;
    public static final int ROLE_ASSISTANT = 1;

    public static final int B_TEXT = 0;
    public static final int B_THINKING = 1;
    public static final int B_TOOL_USE = 2;
    public static final int B_SEARCH_RESULT = 3;
    public static final int B_FETCH_RESULT = 4;

    public static final String S_PENDING = "pending";
    public static final String S_STREAMING = "streaming";
    public static final String S_DONE = "done";
    public static final String S_STOPPED = "stopped";
    public static final String S_ERROR = "error";

    private Models() {
    }

    // ---------------- 引用与来源 ----------------

    public static final class Citation {
        public String url = "";
        public String title = "";
        public String text = "";

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("url", url);
                o.put("title", title);
                o.put("text", text);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Citation from(JSONObject o) {
            Citation c = new Citation();
            c.url = o.optString("url");
            c.title = o.optString("title");
            c.text = o.optString("text");
            return c;
        }
    }

    public static final class Source {
        public String title = "";
        public String url = "";
        public String site = "";
        public String snippet = "";
        public String age = "";

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("title", title);
                o.put("url", url);
                o.put("site", site);
                o.put("snippet", snippet);
                o.put("age", age);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Source from(JSONObject o) {
            Source s = new Source();
            s.title = o.optString("title");
            s.url = o.optString("url");
            s.site = o.optString("site");
            s.snippet = o.optString("snippet");
            s.age = o.optString("age");
            return s;
        }
    }

    // ---------------- 附件 ----------------

    public static final class FileRef {
        public String localPath = "";
        public String serverId = "";
        public String name = "";
        public long size;
        public boolean image;
        public String mime = "";
        public String status = "ready"; // uploading | ready | failed
        public String error = "";

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("p", localPath);
                o.put("id", serverId);
                o.put("n", name);
                o.put("s", size);
                o.put("img", image);
                o.put("m", mime);
                o.put("st", status);
            } catch (Exception ignored) {
            }
            return o;
        }

        static FileRef from(JSONObject o) {
            FileRef f = new FileRef();
            f.localPath = o.optString("p");
            f.serverId = o.optString("id");
            f.name = o.optString("n");
            f.size = o.optLong("s");
            f.image = o.optBoolean("img");
            f.mime = o.optString("m");
            f.status = o.optString("st", "ready");
            return f;
        }
    }

    // ---------------- 内容块 ----------------

    public static final class Block {
        public int type;
        /** 文本 / 思考正文 */
        public String text = "";
        public String signature = "";
        public long startedAt;
        public long endedAt;
        public final List<Citation> citations = new ArrayList<>();

        // 服务端工具调用
        public String id = "";
        public String name = "";
        public String kind = "";
        public final List<String> queries = new ArrayList<>();
        public String url = "";

        // 搜索结果 / 网页阅读结果
        public final List<Source> sources = new ArrayList<>();
        public String fetchTitle = "";
        public boolean failed;
        public String note = "";
        /** 服务端返回的该内容块原始 JSON（下一轮原样回传用） */
        public String raw = "";
        private transient JSONObject rawObj;

        private void ensureRaw() {
            if (rawObj != null) return;
            try {
                rawObj = new JSONObject(raw == null || raw.isEmpty() ? "{}" : raw);
            } catch (Exception e) {
                rawObj = new JSONObject();
            }
        }

        private void putRaw(String key, Object value) {
            ensureRaw();
            try {
                rawObj.put(key, value);
            } catch (Exception ignored) {
            }
        }

        /** 追加正文并同步原始块 */
        public void appendText(String delta) {
            text = text + delta;
            ensureRaw();
            try { if (!rawObj.has("type")) rawObj.put("type", "text"); } catch (Exception ignored) {}
            putRaw("text", text);
        }

        /** 追加思考正文并同步原始块 */
        public void appendThinking(String delta) {
            text = text + delta;
            ensureRaw();
            try {
                if (!rawObj.has("type")) rawObj.put("type", "thinking");
                if (!rawObj.has("signature")) rawObj.put("signature", "");
            } catch (Exception ignored) {}
            putRaw("thinking", text);
        }

        public void appendSignature(String sig) {
            if (sig == null) return;
            signature = signature + sig;
            putRaw("signature", signature);
        }

        /** 追加引用（保留服务端字段） */
        public void addCitationRaw(String url, String title, String citedText) {
            ensureRaw();
            try {
                JSONArray arr = rawObj.optJSONArray("citations");
                if (arr == null) {
                    arr = new JSONArray();
                    rawObj.put("citations", arr);
                }
                arr.put(new JSONObject().put("type", "web_search_result_location")
                        .put("url", url == null ? "" : url)
                        .put("title", title == null ? "" : title)
                        .put("cited_text", citedText == null ? "" : citedText));
            } catch (Exception ignored) {
            }
        }

        /** 落盘/回传前取出最终原始 JSON */
        public String rawJson() {
            if (rawObj != null) {
                raw = rawObj.toString();
                rawObj = null;
            }
            return raw == null ? "" : raw;
        }

        public boolean isThinking() {
            return type == B_THINKING;
        }

        public boolean isEmptyThinking() {
            return type == B_THINKING && text.trim().isEmpty();
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("t", type);
                String rw = rawJson();
                if (!rw.isEmpty()) o.put("rw", rw);
                if (!text.isEmpty()) o.put("x", text);
                if (!signature.isEmpty()) o.put("sg", signature);
                if (startedAt > 0) o.put("s", startedAt);
                if (endedAt > 0) o.put("e", endedAt);
                if (!citations.isEmpty()) {
                    JSONArray a = new JSONArray();
                    for (Citation c : citations) a.put(c.toJson());
                    o.put("ci", a);
                }
                if (!id.isEmpty()) o.put("i", id);
                if (!name.isEmpty()) o.put("n", name);
                if (!kind.isEmpty()) o.put("k", kind);
                if (!queries.isEmpty()) {
                    JSONArray a = new JSONArray();
                    for (String q : queries) a.put(q);
                    o.put("q", a);
                }
                if (!url.isEmpty()) o.put("u", url);
                if (!sources.isEmpty()) {
                    JSONArray a = new JSONArray();
                    for (Source s : sources) a.put(s.toJson());
                    o.put("so", a);
                }
                if (!fetchTitle.isEmpty()) o.put("ft", fetchTitle);
                if (failed) o.put("f", true);
                if (!note.isEmpty()) o.put("no", note);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Block from(JSONObject o) {
            Block b = new Block();
            b.type = o.optInt("t", B_TEXT);
            b.raw = o.optString("rw");
            b.text = o.optString("x");
            b.signature = o.optString("sg");
            b.startedAt = o.optLong("s");
            b.endedAt = o.optLong("e");
            JSONArray ci = o.optJSONArray("ci");
            if (ci != null) for (int i = 0; i < ci.length(); i++) b.citations.add(Citation.from(ci.optJSONObject(i)));
            b.id = o.optString("i");
            b.name = o.optString("n");
            b.kind = o.optString("k");
            JSONArray q = o.optJSONArray("q");
            if (q != null) for (int i = 0; i < q.length(); i++) b.queries.add(q.optString(i));
            b.url = o.optString("u");
            JSONArray so = o.optJSONArray("so");
            if (so != null) for (int i = 0; i < so.length(); i++) b.sources.add(Source.from(so.optJSONObject(i)));
            b.fetchTitle = o.optString("ft");
            b.failed = o.optBoolean("f");
            b.note = o.optString("no");
            return b;
        }
    }

    // ---------------- 消息 ----------------

    public static final class Message {
        public String id = "";
        public int role = ROLE_USER;
        public long createdAt;
        /** 用户提问正文 */
        public String text = "";
        public final List<FileRef> files = new ArrayList<>();
        /** 助手回复块 */
        public final List<Block> blocks = new ArrayList<>();
        public String status = S_DONE;
        public String stopReason = "";
        /** 服务端判定拒答：内容不进入后续上下文 */
        public boolean refused;
        public boolean thinking;
        public boolean search;
        public String model = "";
        public String error = "";
        /** 重新生成产生的其它版本；首个为原始版本。 */
        public final List<Message> alternatives = new ArrayList<>();
        public int activeVersion;
        /** 本轮用量与耗时，用于底部信息条 */
        public Usage usage;
        /** 发送给模型的答复正文快照：后续轮次复用它，避免前缀漂移导致缓存失效 */
        public String sentText = "";
        /** 该轮随用户消息发送的 latest_reminder 内容，随历史一并回传 */
        public String reminder = "";
        /** 协议原生的 assistant 内容（Anthropic: 块数组 JSON；OpenAI: 消息对象 JSON） */
        public String rawJson = "";

        public Message copyShell() {
            Message m = new Message();
            m.role = role;
            m.thinking = thinking;
            m.search = search;
            m.model = model;
            m.status = S_PENDING;
            return m;
        }

        /** 请求上下文用：优先使用发送时的快照，保证服务端前缀缓存可命中。 */
        public String contextText() {
            if (sentText != null && !sentText.isEmpty()) return sentText;
            return plainText();
        }

        public String plainText() {
            StringBuilder sb = new StringBuilder();
            for (Block b : blocks) {
                if (b.type == B_TEXT) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(b.text);
                }
            }
            return sb.toString();
        }

        public boolean hasVisibleText() {
            for (Block b : blocks) {
                if (b.type == B_TEXT && !b.text.trim().isEmpty()) return true;
            }
            return false;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("r", role);
                o.put("c", createdAt);
                if (!text.isEmpty()) o.put("tx", text);
                if (!files.isEmpty()) {
                    JSONArray a = new JSONArray();
                    for (FileRef f : files) a.put(f.toJson());
                    o.put("fi", a);
                }
                JSONArray ba = new JSONArray();
                for (Block b : blocks) ba.put(b.toJson());
                o.put("b", ba);
                o.put("st", status);
                if (!stopReason.isEmpty()) o.put("sr", stopReason);
                if (thinking) o.put("th", true);
                if (search) o.put("se", true);
                if (!model.isEmpty()) o.put("mo", model);
                if (!error.isEmpty()) o.put("er", error);
                if (!alternatives.isEmpty()) {
                    JSONArray a = new JSONArray();
                    for (Message m : alternatives) a.put(m.toJson());
                    o.put("al", a);
                }
                if (activeVersion != 0) o.put("av", activeVersion);
                if (usage != null) o.put("us", usage.toJson());
                if (sentText != null && !sentText.isEmpty()) o.put("sn", sentText);
                if (rawJson != null && !rawJson.isEmpty()) o.put("raw", rawJson);
                if (reminder != null && !reminder.isEmpty()) o.put("lr", reminder);
                if (refused) o.put("rf", true);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Message from(JSONObject o) {
            Message m = new Message();
            m.id = o.optString("id");
            m.role = o.optInt("r", ROLE_USER);
            m.createdAt = o.optLong("c");
            m.text = o.optString("tx");
            JSONArray fi = o.optJSONArray("fi");
            if (fi != null) for (int i = 0; i < fi.length(); i++) m.files.add(FileRef.from(fi.optJSONObject(i)));
            JSONArray ba = o.optJSONArray("b");
            if (ba != null) for (int i = 0; i < ba.length(); i++) m.blocks.add(Block.from(ba.optJSONObject(i)));
            m.status = o.optString("st", S_DONE);
            m.stopReason = o.optString("sr");
            m.thinking = o.optBoolean("th");
            m.search = o.optBoolean("se");
            m.model = o.optString("mo");
            m.error = o.optString("er");
            JSONArray al = o.optJSONArray("al");
            if (al != null) for (int i = 0; i < al.length(); i++) m.alternatives.add(from(al.optJSONObject(i)));
            m.activeVersion = o.optInt("av");
            m.usage = Usage.from(o.optJSONObject("us"));
            m.sentText = o.optString("sn");
            m.rawJson = o.optString("raw");
            m.reminder = o.optString("lr");
            m.refused = o.optBoolean("rf", false);
            return m;
        }
    }

    // ---------------- 会话 ----------------

    public static final class Session {
        public String id = "";
        public String title = "";
        public long createdAt;
        public long updatedAt;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("t", title);
                o.put("c", createdAt);
                o.put("u", updatedAt);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Session from(JSONObject o) {
            Session s = new Session();
            s.id = o.optString("id");
            s.title = o.optString("t");
            s.createdAt = o.optLong("c");
            s.updatedAt = o.optLong("u");
            return s;
        }
    }
}
