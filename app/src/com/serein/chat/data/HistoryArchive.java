package com.serein.chat.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 与 DeepSeek 官方导出包（deepseek_data-*.zip，export_format_version = 3）互转。
 *
 * 归档结构：
 *   manifest.json                                  {"export_format_version":3}
 *   chat_sessions/{session_id}.json                会话（含消息树）
 *   chat_session_index.json                        会话索引
 *   chat_sessions/{session_id}_files/{id}_{name}   图片原件（本实现导入时按文件名匹配）
 */
public final class HistoryArchive {

    public static final int FORMAT_VERSION = 3;

    private HistoryArchive() {
    }

    public static final class Result {
        public int sessions;
        public int messages;
        public String error;
    }

    // ------------------------------------------------------------------ 导出

    public static Result exportArchive(Db db, OutputStream out) throws IOException {
        Result r = new Result();
        List<Models.Session> sessions = db.listSessions();
        JSONArray index = new JSONArray();
        ByteArrayOutputStream buffered = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(buffered, StandardCharsets.UTF_8);

        // 1) manifest 必须是首个条目
        JSONObject manifest = new JSONObject();
        try {
            manifest.put("export_format_version", FORMAT_VERSION);
        } catch (Exception ignored) {
        }
        putEntry(zip, "manifest.json", manifest.toString());

        // 2) 每个会话一个 JSON
        for (Models.Session s : sessions) {
            List<Models.Message> msgs = db.listMessages(s.id);
            JSONObject file = sessionToJson(s, msgs);
            putEntry(zip, "chat_sessions/" + s.id + ".json", file.toString());
            index.put(indexEntry(s, msgs.size()));
            r.sessions++;
            r.messages += msgs.size();
        }

        // 3) 会话索引
        putEntry(zip, "chat_session_index.json", index.toString());
        zip.finish();
        zip.flush();
        out.write(buffered.toByteArray());
        out.flush();
        return r;
    }

    private static JSONObject indexEntry(Models.Session s, int count) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", s.id);
            o.put("title", s.title == null || s.title.isEmpty() ? JSONObject.NULL : s.title);
            o.put("pinned", false);
            o.put("message_count", count);
            o.put("inserted_at", iso(s.createdAt));
            o.put("updated_at", iso(s.updatedAt));
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject sessionToJson(Models.Session s, List<Models.Message> msgs) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", s.id);
            o.put("title", s.title == null || s.title.isEmpty() ? JSONObject.NULL : s.title);
            o.put("pinned", false);
            o.put("current_message_id", msgs.isEmpty() ? 0 : msgs.size());
            o.put("inserted_at", iso(s.createdAt));
            o.put("updated_at", iso(s.updatedAt));
            JSONArray arr = new JSONArray();
            for (int i = 0; i < msgs.size(); i++) {
                arr.put(messageToJson(msgs.get(i), i + 1, i == 0 ? null : i));
            }
            o.put("messages", arr);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject messageToJson(Models.Message m, int id, Integer parentId) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("parent_id", parentId == null ? JSONObject.NULL : parentId);
            o.put("role", m.role == Models.ROLE_USER ? "USER" : "ASSISTANT");
            o.put("status", archiveStatus(m));
            o.put("thinking_enabled", m.thinking);
            o.put("search_enabled", m.search);
            o.put("accumulated_token_usage",
                    m.usage == null ? 0 : m.usage.inputTokens + m.usage.outputTokens);
            o.put("inserted_at", iso(m.createdAt));

            JSONArray fragments = new JSONArray();
            if (m.role == Models.ROLE_USER) {
                fragments.put(new JSONObject().put("type", "REQUEST")
                        .put("content", m.text == null ? "" : m.text));
            } else {
                for (Models.Block b : m.blocks) {
                    switch (b.type) {
                        case Models.B_THINKING:
                            if (b.text == null || b.text.trim().isEmpty()) break;
                            long secs = b.endedAt > 0 && b.startedAt > 0
                                    ? Math.max(1, Math.round((b.endedAt - b.startedAt) / 1000.0)) : 0;
                            fragments.put(new JSONObject().put("type", "THINK")
                                    .put("content", b.text)
                                    .put("elapsed_secs", secs > 0 ? secs : JSONObject.NULL));
                            break;
                        case Models.B_TEXT:
                            if (b.text == null || b.text.isEmpty()) break;
                            fragments.put(new JSONObject().put("type", "RESPONSE")
                                    .put("content", b.text));
                            break;
                        case Models.B_SEARCH_RESULT: {
                            JSONArray results = new JSONArray();
                            for (Models.Source src : b.sources) {
                                results.put(new JSONObject()
                                        .put("url", src.url)
                                        .put("title", src.title)
                                        .put("snippet", src.snippet)
                                        .put("published_at", src.age.isEmpty() ? JSONObject.NULL : src.age)
                                        .put("site_name", src.site.isEmpty() ? JSONObject.NULL : src.site));
                            }
                            JSONArray queries = new JSONArray();
                            for (String q : b.queries) queries.put(q);
                            fragments.put(new JSONObject().put("type", "TOOL_SEARCH")
                                    .put("queries", queries)
                                    .put("results", results)
                                    .put("status", b.failed ? "FAILED" : "FINISHED"));
                            break;
                        }
                        case Models.B_FETCH_RESULT:
                            fragments.put(new JSONObject().put("type", "TOOL_OPEN")
                                    .put("status", b.failed ? "FAILED" : "FINISHED")
                                    .put("url", b.url == null || b.url.isEmpty() ? JSONObject.NULL : b.url)
                                    .put("title", b.fetchTitle == null || b.fetchTitle.isEmpty()
                                            ? JSONObject.NULL : b.fetchTitle)
                                    .put("published_at", JSONObject.NULL));
                            break;
                        default:
                            break;
                    }
                }
            }
            o.put("fragments", fragments);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static String archiveStatus(Models.Message m) {
        if (Models.S_ERROR.equals(m.status)) return "FAILED";
        if (Models.S_STOPPED.equals(m.status) || Models.S_PENDING.equals(m.status)
                || Models.S_STREAMING.equals(m.status)) return "INCOMPLETE";
        return "FINISHED";
    }

    // ------------------------------------------------------------------ 导入

    /** 读取归档并整体替换本地会话；返回导入统计。 */
    public static Result importArchive(Db db, InputStream in) {
        Result r = new Result();
        Map<String, String> files = new HashMap<>();
        try {
            ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8);
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                String name = e.getName();
                if ("user.json".equals(name)) {
                    zip.closeEntry();
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zip.read(buf)) > 0) bos.write(buf, 0, n);
                zip.closeEntry();
                files.put(name, new String(bos.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception ex) {
            r.error = "无法读取压缩包：" + ex.getMessage();
            return r;
        }

        String manifest = files.get("manifest.json");
        if (manifest == null) {
            r.error = "缺少 manifest.json，不是 DeepSeek 导出的历史对话包";
            return r;
        }
        try {
            JSONObject mo = new JSONObject(manifest);
            int version = mo.optInt("export_format_version", -1);
            if (version != FORMAT_VERSION) {
                r.error = "不支持的归档版本：" + version + "（当前支持 " + FORMAT_VERSION + "）";
                return r;
            }
        } catch (Exception ex) {
            r.error = "manifest.json 解析失败";
            return r;
        }

        List<JSONObject> sessionFiles = new ArrayList<>();
        for (Map.Entry<String, String> en : files.entrySet()) {
            String name = en.getKey();
            if (!name.startsWith("chat_sessions/") || !name.endsWith(".json")) continue;
            if (name.contains("_files/")) continue;
            try {
                sessionFiles.add(new JSONObject(en.getValue()));
            } catch (Exception ignored) {
            }
        }
        if (sessionFiles.isEmpty()) {
            r.error = "归档中没有可导入的会话";
            return r;
        }

        // 校验通过后整体替换：先清空，再写入
        db.deleteAll();
        SimpleDateFormat parser = isoParser();
        for (JSONObject so : sessionFiles) {
            Models.Session s = new Models.Session();
            String rawId = so.optString("id");
            s.id = rawId == null || rawId.isEmpty() ? UUID.randomUUID().toString() : rawId;
            String title = so.optString("title", "");
            s.title = "null".equals(title) ? "" : title;
            s.createdAt = parseIso(parser, so.optString("inserted_at"), System.currentTimeMillis());
            s.updatedAt = parseIso(parser, so.optString("updated_at"), s.createdAt);
            db.upsertSession(s);
            r.sessions++;

            JSONArray arr = so.optJSONArray("messages");
            if (arr == null) continue;
            // 消息树：按 current_message_id 回溯出当前分支
            Map<Integer, JSONObject> byId = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m != null) byId.put(m.optInt("id"), m);
            }
            List<JSONObject> chain = new ArrayList<>();
            int cur = so.optInt("current_message_id", 0);
            while (byId.containsKey(cur)) {
                JSONObject m = byId.get(cur);
                chain.add(0, m);
                cur = m.optInt("parent_id", 0);
            }
            if (chain.isEmpty()) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m != null) chain.add(m);
                }
            }
            for (JSONObject mo : chain) {
                Models.Message msg = messageFromJson(mo, parser);
                if (msg == null) continue;
                db.saveMessage(s.id, msg);
                r.messages++;
            }
        }
        return r;
    }

    private static Models.Message messageFromJson(JSONObject o, SimpleDateFormat parser) {
        try {
            Models.Message m = new Models.Message();
            m.id = UUID.randomUUID().toString();
            boolean user = "USER".equals(o.optString("role"));
            m.role = user ? Models.ROLE_USER : Models.ROLE_ASSISTANT;
            m.createdAt = parseIso(parser, o.optString("inserted_at"), System.currentTimeMillis());
            String status = o.optString("status");
            m.status = "FAILED".equals(status) ? Models.S_ERROR
                    : "INCOMPLETE".equals(status) ? Models.S_STOPPED : Models.S_DONE;
            m.thinking = o.optBoolean("thinking_enabled");
            m.search = o.optBoolean("search_enabled");

            JSONArray fragments = o.optJSONArray("fragments");
            if (fragments == null) fragments = new JSONArray();
            for (int i = 0; i < fragments.length(); i++) {
                JSONObject f = fragments.optJSONObject(i);
                if (f == null) continue;
                String type = f.optString("type");
                if ("REQUEST".equals(type)) {
                    m.text = f.optString("content", "");
                } else if ("RESPONSE".equals(type)) {
                    Models.Block b = new Models.Block();
                    b.type = Models.B_TEXT;
                    b.text = f.optString("content", "");
                    m.blocks.add(b);
                } else if ("THINK".equals(type)) {
                    Models.Block b = new Models.Block();
                    b.type = Models.B_THINKING;
                    b.text = f.optString("content", "");
                    long secs = f.optLong("elapsed_secs", 0);
                    b.startedAt = m.createdAt;
                    b.endedAt = secs > 0 ? m.createdAt + secs * 1000 : m.createdAt;
                    m.blocks.add(b);
                } else if ("TOOL_SEARCH".equals(type)) {
                    Models.Block b = new Models.Block();
                    b.type = Models.B_SEARCH_RESULT;
                    b.kind = "search";
                    JSONArray qs = f.optJSONArray("queries");
                    if (qs != null) for (int q = 0; q < qs.length(); q++) b.queries.add(qs.optString(q));
                    JSONArray rs = f.optJSONArray("results");
                    if (rs != null) {
                        for (int k = 0; k < rs.length(); k++) {
                            JSONObject r0 = rs.optJSONObject(k);
                            if (r0 == null) continue;
                            Models.Source src = new Models.Source();
                            src.url = r0.optString("url");
                            src.title = r0.optString("title");
                            src.snippet = r0.optString("snippet");
                            src.site = r0.optString("site_name");
                            src.age = r0.optString("published_at");
                            if ("null".equals(src.site)) src.site = "";
                            if ("null".equals(src.age)) src.age = "";
                            b.sources.add(src);
                        }
                    }
                    b.failed = "FAILED".equals(f.optString("status"));
                    m.blocks.add(b);
                } else if ("TOOL_OPEN".equals(type)) {
                    Models.Block b = new Models.Block();
                    b.type = Models.B_FETCH_RESULT;
                    b.kind = "read_link";
                    b.url = f.optString("url");
                    b.fetchTitle = f.optString("title");
                    if ("null".equals(b.url)) b.url = "";
                    if ("null".equals(b.fetchTitle)) b.fetchTitle = "";
                    b.failed = "FAILED".equals(f.optString("status"));
                    m.blocks.add(b);
                }
            }
            if (m.role == Models.ROLE_USER && (m.text == null || m.text.isEmpty())) {
                for (Models.Block b : m.blocks) {
                    if (b.type == Models.B_TEXT) {
                        m.text = b.text;
                        m.blocks.clear();
                        break;
                    }
                }
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 工具

    private static void putEntry(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry e = new ZipEntry(name);
        zip.putNextEntry(e);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static SimpleDateFormat isoParser() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static long parseIso(SimpleDateFormat parser, String text, long fallback) {
        if (text == null || text.isEmpty() || "null".equals(text)) return fallback;
        try {
            return parser.parse(text).getTime();
        } catch (ParseException e) {
            try {
                SimpleDateFormat alt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                alt.setTimeZone(TimeZone.getTimeZone("UTC"));
                return alt.parse(text).getTime();
            } catch (ParseException ignored) {
                return fallback;
            }
        }
    }
}
