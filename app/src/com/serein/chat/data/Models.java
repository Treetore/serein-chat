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
