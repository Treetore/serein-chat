package com.serein.chat.api;

import com.serein.chat.data.Models;
import com.serein.chat.data.Provider;
import com.serein.chat.data.Usage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * 双协议客户端：Anthropic 兼容（/v1/messages）与 OpenAI 兼容（/v1/chat/completions），
 * 归一化到同一套 {@link Listener} 回调，上层渲染无需分支。
 */
public final class Api {

    private static final String UA = "DeepSeek-Android/1.0 (Android)";
    private static final String FILES_BETA = "files-api-2025-04-14";
    private static final int MAX_TOKENS = 8192;

    private Api() {
    }

    public interface Listener {
        void onMessageStart(String messageId, String model);

        void onBlockStart(int index, Models.Block block);

        void onTextDelta(int index, String text);

        void onThinkingDelta(int index, String text);

        void onSignatureDelta(int index, String signature);

        void onCitation(int index, Models.Citation citation);

        void onBlockStop(int index);

        void onUsage(Usage usage);

        void onMessageEnd(String stopReason, String title, String failureNote);

        void onError(ApiException e);
    }

    public static void chat(Provider p, ChatRequest req, Listener l, AtomicBoolean cancelled) {
        try {
            if (Provider.PROTO_ANTHROPIC.equals(p.protocol)) {
                streamAnthropic(p, req, l, cancelled);
            } else if (Provider.PROTO_RESPONSES.equals(p.protocol)) {
                streamResponses(p, req, l, cancelled);
            } else {
                streamOpenAi(p, req, l, cancelled);
            }
        } catch (ApiException e) {
            l.onError(e);
        } catch (Exception e) {
            l.onError(new ApiException(ApiException.NETWORK,
                    e.getMessage() == null ? e.toString() : e.getMessage(), 0));
        }
    }

    // ------------------------------------------------------------------ Anthropic 兼容

    private static String anthropicUrl(Provider p) {
        String base = trimSlash(p.baseUrl);
        if (p.official()) return base + "/anthropic/v1/messages";
        if (base.endsWith("/v1")) return base + "/messages";
        return base + "/v1/messages";
    }

    /** Responses API（第三种协议）：POST {base}/responses，语义化 SSE 事件。 */
    private static void streamResponses(Provider p, ChatRequest req, Listener l,
                                        AtomicBoolean cancelled) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", req.model);
        body.put("stream", true);
        if (req.system != null && !req.system.isEmpty()) body.put("instructions", req.system);
        if (req.temperature > 0) {
            body.put("temperature", Math.round(req.temperature * 10) / 10.0);
        }
        if (req.maxTokens > 0) body.put("max_output_tokens", req.maxTokens);
        JSONObject reasoning = new JSONObject();
        if (req.thinking && req.effort != null && !req.effort.isEmpty()) {
            reasoning.put("effort", req.effort);
        } else if (!req.thinking) {
            reasoning.put("effort", "none");
        }
        body.put("reasoning", reasoning);
        if (p.official()) {
            JSONArray tools = new JSONArray();
            tools.put(new JSONObject().put("type", "deepseek_read_url"));
            if (req.search) tools.put(new JSONObject().put("type", "deepseek_web_search"));
            body.put("tools", tools);
            body.put("safety_check", new JSONObject().put("level", "high"));
            if (req.withTitle) body.put("title", new JSONObject().put("enabled", true));
        }
        body.put("input", responseInput(req));

        HttpURLConnection conn = open(trimSlash(p.baseUrl) + "/responses", p.apiKey, true);
        try {
            writeBody(conn, body.toString());
            int code = conn.getResponseCode();
            if (code >= 400) throw readError(conn, code);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(decoded(conn), StandardCharsets.UTF_8), 8192);
            String line;
            int textIndex = -1;
            int thinkIndex = -1;
            int nextIndex = 0;
            boolean started = false;
            String finish = "end_turn";
            Usage usage = null;

            while ((line = reader.readLine()) != null) {
                if (cancelled != null && cancelled.get()) return;
                if (line.isEmpty() || line.charAt(0) == ':') continue;
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                JSONObject o = parse(data);
                if (o == null) continue;
                String type = str(o, "type");
                if (!started) {
                    started = true;
                    JSONObject resp = o.optJSONObject("response");
                    String id = resp == null ? "" : str(resp, "id");
                    l.onMessageStart(id, req.model);
                }
                if ("response.reasoning_text.delta".equals(type)) {
                    String d = str(o, "delta");
                    if (!d.isEmpty()) {
                        if (thinkIndex < 0) {
                            thinkIndex = nextIndex++;
                            Models.Block b = new Models.Block();
                            b.type = Models.B_THINKING;
                            b.startedAt = System.currentTimeMillis();
                            l.onBlockStart(thinkIndex, b);
                        }
                        l.onThinkingDelta(thinkIndex, d);
                    }
                    continue;
                }
                if ("response.output_text.delta".equals(type)) {
                    String d = str(o, "delta");
                    if (!d.isEmpty()) {
                        if (thinkIndex >= 0) {
                            l.onBlockStop(thinkIndex);
                            thinkIndex = -1;
                        }
                        if (textIndex < 0) {
                            textIndex = nextIndex++;
                            Models.Block b = new Models.Block();
                            b.type = Models.B_TEXT;
                            l.onBlockStart(textIndex, b);
                        }
                        l.onTextDelta(textIndex, d);
                    }
                    continue;
                }
                if ("response.output_item.done".equals(type)) {
                    JSONObject item = o.optJSONObject("item");
                    if (item != null && "web_search_call".equals(str(item, "type"))) {
                        Models.Block use = new Models.Block();
                        use.type = Models.B_TOOL_USE;
                        use.id = str(item, "id");
                        use.name = "deepseek_web_search";
                        use.kind = "search";
                        JSONObject action = item.optJSONObject("action");
                        if (action != null) {
                            JSONArray qs = action.optJSONArray("queries");
                            if (qs != null) for (int q = 0; q < qs.length(); q++) use.queries.add(qs.optString(q));
                        }
                        int ui = nextIndex++;
                        l.onBlockStart(ui, use);
                        l.onBlockStop(ui);
                        JSONArray srcs = item.optJSONArray("sources");
                        if (srcs != null && srcs.length() > 0) {
                            Models.Block res = new Models.Block();
                            res.type = Models.B_SEARCH_RESULT;
                            res.kind = "search";
                            res.id = use.id;
                            for (int k = 0; k < srcs.length(); k++) {
                                JSONObject so = srcs.optJSONObject(k);
                                if (so == null) continue;
                                Models.Source src = new Models.Source();
                                src.title = str(so, "title");
                                src.url = str(so, "url");
                                src.snippet = str(so, "snippet");
                                res.sources.add(src);
                            }
                            int ri = nextIndex++;
                            l.onBlockStart(ri, res);
                            l.onBlockStop(ri);
                        }
                    }
                    continue;
                }
                if ("response.completed".equals(type) || "response.incomplete".equals(type)
                        || "response.failed".equals(type)) {
                    JSONObject resp = o.optJSONObject("response");
                    if (resp != null) {
                        Usage u = responseUsage(resp.optJSONObject("usage"));
                        if (u != null) {
                            usage = u;
                            l.onUsage(u);
                        }
                        if ("response.failed".equals(type)) {
                            JSONObject err = resp.optJSONObject("error");
                            String msg = err == null ? "响应失败" : str(err, "message");
                            l.onError(new ApiException(ApiException.STREAM, msg, 0));
                            return;
                        }
                    }
                    if ("response.incomplete".equals(type)) finish = "max_tokens";
                    break;
                }
            }
            if (thinkIndex >= 0) l.onBlockStop(thinkIndex);
            if (textIndex >= 0) l.onBlockStop(textIndex);
            if (usage != null) l.onUsage(usage);
            if ("refusal".equals(finish)) {
                l.onMessageEnd(finish, null, "refusal");
            } else {
                l.onMessageEnd(finish, null, null);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Responses API 的 input item 列表：逐轮 message，正文用字符串形式。 */
    private static JSONArray responseInput(ChatRequest req) throws Exception {
        JSONArray arr = new JSONArray();
        String pendingReminder = null;
        for (Turn t : req.turns) {
            if (t.role == null) continue;
            String text = t.text == null ? "" : t.text;
            if (Turn.LATEST_REMINDER.equals(t.role)) {
                pendingReminder = text;
                continue;
            }
            if (Turn.ASSISTANT.equals(t.role) && text.isEmpty()) continue;
            boolean user = Turn.USER.equals(t.role);
            if (user && pendingReminder != null) {
                text = pendingReminder + (text.isEmpty() ? "" : "\n\n" + text);
                pendingReminder = null;
            }
            JSONObject m = new JSONObject();
            m.put("type", "message");
            m.put("role", user ? "user" : "assistant");
            m.put("content", text);
            arr.put(m);
        }
        return arr;
    }

    /** Responses API 用量映射。 */
    private static Usage responseUsage(JSONObject u) {
        if (u == null) return null;
        Usage out = new Usage();
        out.inputTokens = u.optInt("input_tokens");
        out.outputTokens = u.optInt("output_tokens");
        JSONObject in = u.optJSONObject("input_tokens_details");
        if (in != null) out.cacheHit = in.optInt("cached_tokens", 0);
        out.cacheMiss = Math.max(0, out.inputTokens - out.cacheHit);
        if (out.inputTokens == 0 && out.outputTokens == 0) return null;
        return out;
    }

    private static void streamAnthropic(Provider p, ChatRequest req, Listener l,
                                        AtomicBoolean cancelled) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", req.model);
        if (req.system != null && !req.system.isEmpty()) body.put("system", req.system);
        body.put("stream", true);
        JSONObject thinking = new JSONObject();
        thinking.put("type", req.thinking ? "enabled" : "disabled");
        body.put("thinking", thinking);
        // 思考强度走 output_config.effort（low/high/max），budget_tokens 不是强度开关
        if (req.thinking && req.effort != null && !req.effort.isEmpty()) {
            body.put("output_config", new JSONObject().put("effort", req.effort));
        }
        if (req.temperature > 0) body.put("temperature", req.temperature);
        if (req.maxTokens > 0) body.put("max_tokens", req.maxTokens);
        else if (!p.official()) body.put("max_tokens", MAX_TOKENS);

        if (p.official()) {
            JSONArray tools = new JSONArray();
            tools.put(new JSONObject().put("type", "deepseek_read_url").put("name", "deepseek_read_url"));
            if (req.search) {
                tools.put(new JSONObject().put("type", "deepseek_web_search").put("name", "deepseek_web_search"));
            }
            body.put("tools", tools);
            body.put("safety_check", new JSONObject().put("level", "high"));
            if (req.withTitle) body.put("title", new JSONObject().put("enabled", true));
        }

        JSONArray prefix;
        try {
            prefix = req.cachedPrefix == null ? null : new JSONArray(req.cachedPrefix);
        } catch (Exception e) {
            prefix = null;
        }
        JSONArray msgs = prefix != null && prefix.length() == Math.max(0, req.turns.size() - 1)
                ? prefix
                : anthropicMessages(p, req, Math.max(0, req.turns.size() - 1));
        if (!req.turns.isEmpty()) {
            msgs.put(anthropicTurn(p, req, req.turns.get(req.turns.size() - 1)));
        }
        body.put("messages", msgs);

        HttpURLConnection conn = open(anthropicUrl(p), p.apiKey, true);
        try {
            writeBody(conn, body.toString());
            int code = conn.getResponseCode();
            if (code >= 400) throw readError(conn, code);
            String ct = conn.getContentType() == null ? "" : conn.getContentType();
            InputStream in = decoded(conn);
            if (ct.contains("application/json") && !ct.contains("event-stream")) {
                emitAnthropicWhole(readAll(in), l);
                return;
            }
            if (!ct.contains("text/event-stream")) {
                throw new ApiException(ApiException.PROTOCOL, "响应不是事件流：" + ct, code);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 8192);
            Sse decoder = new Sse();
            char[] buf = new char[4096];
            String stopReason = "";
            String title = null;
            boolean finished = false;

            while (!finished) {
                if (cancelled != null && cancelled.get()) return;
                int n = reader.read(buf);
                if (n < 0) break;
                for (Sse.Event ev : decoder.push(new String(buf, 0, n))) {
                    JSONObject o = parse(ev.data);
                    if (o == null) continue;
                    JSONObject errBody = o.optJSONObject("error");
                    if (errBody != null) {
                        l.onError(new ApiException(ApiException.STREAM,
                                errBody.optString("message", ev.data), 0));
                        return;
                    }
                    String type = o.optString("type");
                    if ("message_start".equals(type)) {
                        JSONObject msg = o.optJSONObject("message");
                        if (msg != null) {
                            l.onMessageStart(msg.optString("id"), msg.optString("model"));
                            Usage u = anthropicUsage(msg.optJSONObject("usage"));
                            if (u != null) l.onUsage(u);
                        }
                    } else if ("content_block_start".equals(type)) {
                        Models.Block b = readBlock(o.optJSONObject("content_block"));
                        if (b != null) l.onBlockStart(o.optInt("index"), b);
                    } else if ("content_block_delta".equals(type)) {
                        int idx = o.optInt("index");
                        JSONObject d = o.optJSONObject("delta");
                        if (d == null) continue;
                        String dt = d.optString("type");
                        if ("text_delta".equals(dt)) {
                            String dtText = str(d, "text");
                            if (!dtText.isEmpty()) l.onTextDelta(idx, dtText);
                        } else if ("thinking_delta".equals(dt)) {
                            String dtThink = str(d, "thinking");
                            if (!dtThink.isEmpty()) l.onThinkingDelta(idx, dtThink);
                        } else if ("signature_delta".equals(dt)) {
                            l.onSignatureDelta(idx, d.optString("signature"));
                        } else if ("citations_delta".equals(dt)) {
                            JSONObject c = d.optJSONObject("citation");
                            if (c != null) {
                                Models.Citation cit = new Models.Citation();
                                cit.url = c.optString("url");
                                cit.title = c.optString("title");
                                cit.text = c.optString("cited_text");
                                l.onCitation(idx, cit);
                            }
                        }
                    } else if ("content_block_stop".equals(type)) {
                        l.onBlockStop(o.optInt("index"));
                    } else if ("message_delta".equals(type)) {
                        JSONObject d = o.optJSONObject("delta");
                        if (d != null) {
                            stopReason = d.optString("stop_reason");
                            if (d.has("title")) title = d.optString("title");
                        }
                        Usage u = anthropicUsage(o.optJSONObject("usage"));
                        if (u != null) l.onUsage(u);
                    } else if ("message_stop".equals(type)) {
                        finished = true;
                    }
                    if (finished) break;
                }
            }
            l.onMessageEnd(stopReason, title,
                    "refusal".equals(stopReason) ? "refusal" : null);
        } finally {
            conn.disconnect();
        }
    }

    private static Usage anthropicUsage(JSONObject u) {
        if (u == null) return null;
        Usage out = new Usage();
        int cacheRead = u.optInt("cache_read_input_tokens");
        int cacheCreate = u.optInt("cache_creation_input_tokens");
        int input = u.optInt("input_tokens");
        out.cacheHit = cacheRead;
        out.cacheMiss = input;
        out.inputTokens = input + cacheRead + cacheCreate;
        out.outputTokens = u.optInt("output_tokens");
        if (out.inputTokens == 0 && out.outputTokens == 0) return null;
        return out;
    }

    // ------------------------------------------------------------------ OpenAI 兼容

    private static String openAiUrl(Provider p) {
        String base = trimSlash(p.baseUrl);
        if (base.endsWith("/chat/completions")) return base;
        if (base.endsWith("/v1")) return base + "/chat/completions";
        return base + "/v1/chat/completions";
    }

    private static void streamOpenAi(Provider p, ChatRequest req, Listener l,
                                     AtomicBoolean cancelled) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", req.model);
        body.put("stream", true);
        body.put("stream_options", new JSONObject().put("include_usage", true));
        if (req.temperature > 0) body.put("temperature", req.temperature);
        if (req.maxTokens > 0) body.put("max_tokens", req.maxTokens);
        if (p.official()) {
            // 思考开关与强度（官方兼容层字段，三方服务不猜测）
            body.put("thinking", new JSONObject().put("type", req.thinking ? "enabled" : "disabled"));
            if (req.thinking && req.effort != null && !req.effort.isEmpty()) {
                body.put("reasoning_effort", req.effort);
            }
            // 服务端联网工具：Chat Completions 形态为 {"type": "deepseek_xxx"}
            JSONArray tools = new JSONArray();
            tools.put(new JSONObject().put("type", "deepseek_read_url"));
            if (req.search) {
                tools.put(new JSONObject().put("type", "deepseek_web_search"));
            }
            body.put("tools", tools);
            body.put("safety_check", new JSONObject().put("level", "high"));
            if (req.withTitle) body.put("title", new JSONObject().put("enabled", true));
        }

        JSONArray prefix;
        try {
            prefix = req.cachedPrefix == null ? null : new JSONArray(req.cachedPrefix);
        } catch (Exception e) {
            prefix = null;
        }
        JSONArray msgs = prefix != null && prefix.length() == Math.max(0, req.turns.size() - 1)
                + (req.system == null || req.system.isEmpty() ? 0 : 1)
                ? prefix
                : openAiMessages(p, req, Math.max(0, req.turns.size() - 1));
        if (!req.turns.isEmpty()) {
            msgs.put(openAiTurn(p, req, req.turns.get(req.turns.size() - 1)));
        }
        body.put("messages", msgs);
        HttpURLConnection conn = open(openAiUrl(p), p.apiKey, true);
        try {
            writeBody(conn, body.toString());
            int code = conn.getResponseCode();
            if (code >= 400) throw readError(conn, code);
            String ct = conn.getContentType() == null ? "" : conn.getContentType();
            InputStream in = decoded(conn);
            if (ct.contains("application/json") && !ct.contains("event-stream")) {
                emitOpenAiWhole(readAll(in), l);
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 8192);
            String line;
            int textIndex = -1;
            int thinkIndex = -1;
            int nextIndex = 0;
            boolean started = false;
            String finish = "";
            Usage usage = null;

            while ((line = reader.readLine()) != null) {
                if (cancelled != null && cancelled.get()) return;
                if (line.isEmpty() || line.charAt(0) == ':') continue;
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                JSONObject o = parse(data);
                if (o == null) continue;
                JSONObject err = o.optJSONObject("error");
                if (err != null) {
                    l.onError(new ApiException(ApiException.STREAM, err.optString("message", data), 0));
                    return;
                }
                if (!started) {
                    started = true;
                    l.onMessageStart(o.optString("id"), o.optString("model", req.model));
                }
                Usage chunkUsage = openAiUsage(o.optJSONObject("usage"));
                if (chunkUsage != null) {
                    usage = chunkUsage;
                    l.onUsage(chunkUsage);
                }
                JSONArray choices = o.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject choice = choices.optJSONObject(0);
                if (choice == null) continue;
                String fr = str(choice, "finish_reason");
                if (!fr.isEmpty()) finish = fr;
                JSONObject delta = choice.optJSONObject("delta");
                if (delta == null) continue;
                String reasoning = str(delta, "reasoning_content");
                if (!reasoning.isEmpty()) {
                    if (thinkIndex < 0) {
                        thinkIndex = nextIndex++;
                        Models.Block b = new Models.Block();
                        b.type = Models.B_THINKING;
                        b.startedAt = System.currentTimeMillis();
                        l.onBlockStart(thinkIndex, b);
                    }
                    l.onThinkingDelta(thinkIndex, reasoning);
                }
                if (!fr.isEmpty()) {
                    // 工具调用与引用只在带 finish_reason 的最后一个 chunk 下发
                    int[] idx = new int[]{nextIndex, textIndex};
                    readServerTools(delta, l, idx);
                    nextIndex = idx[0];
                    textIndex = idx[1];
                }
                String content = str(delta, "content");
                if (!content.isEmpty()) {
                    if (thinkIndex >= 0) {
                        l.onBlockStop(thinkIndex);
                        thinkIndex = -1;
                    }
                    if (textIndex < 0) {
                        textIndex = nextIndex++;
                        Models.Block b = new Models.Block();
                        b.type = Models.B_TEXT;
                        l.onBlockStart(textIndex, b);
                    }
                    l.onTextDelta(textIndex, content);
                }
            }
            if (thinkIndex >= 0) l.onBlockStop(thinkIndex);
            if (textIndex >= 0) l.onBlockStop(textIndex);
            if (usage != null) l.onUsage(usage);
            String stop = finish.isEmpty() ? "end_turn" : finish;
            l.onMessageEnd(stop, null,
                    "content_filter".equals(finish) || "refusal".equals(finish) ? "refusal" : null);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 解析 Chat Completions 的服务端工具结果：
     * server_tool_calls[].calls[] 生成工具/来源块，annotations[] 生成引用。
     * idx[0] = 下一个可用块下标，idx[1] = 当前正文块下标（可为 -1）。
     */
    private static void readServerTools(JSONObject delta, Listener l, int[] idx) {
        try {
            JSONArray steps = delta.optJSONArray("server_tool_calls");
            if (steps != null) {
                for (int i = 0; i < steps.length(); i++) {
                    JSONObject step = steps.optJSONObject(i);
                    if (step == null) continue;
                    String stepReasoning = str(step, "reasoning_content");
                    if (!stepReasoning.isEmpty()) {
                        Models.Block t = new Models.Block();
                        t.type = Models.B_THINKING;
                        int ti = idx[0]++;
                        t.startedAt = System.currentTimeMillis();
                        l.onBlockStart(ti, t);
                        l.onThinkingDelta(ti, stepReasoning);
                        l.onBlockStop(ti);
                    }
                    JSONArray calls = step.optJSONArray("calls");
                    if (calls == null) continue;
                    for (int c = 0; c < calls.length(); c++) {
                        JSONObject call = calls.optJSONObject(c);
                        if (call == null) continue;
                        String type = str(call, "type");
                        String kind = str(call, "kind");
                        JSONObject action = call.optJSONObject("action");
                        boolean isSearch = type.contains("web_search");

                        Models.Block use = new Models.Block();
                        use.type = Models.B_TOOL_USE;
                        use.id = str(call, "id");
                        use.name = type;
                        use.kind = kind;
                        if (action != null) {
                            JSONArray qs = action.optJSONArray("queries");
                            if (qs != null) for (int q = 0; q < qs.length(); q++) use.queries.add(qs.optString(q));
                            use.url = str(action, "url");
                        }
                        int ui = idx[0]++;
                        l.onBlockStart(ui, use);
                        l.onBlockStop(ui);

                        if (isSearch) {
                            JSONArray sources = call.optJSONArray("sources");
                            if (sources != null) {
                                Models.Block res = new Models.Block();
                                res.type = Models.B_SEARCH_RESULT;
                                res.kind = kind;
                                res.id = use.id;
                                for (int k = 0; k < sources.length(); k++) {
                                    JSONObject so = sources.optJSONObject(k);
                                    if (so == null) continue;
                                    Models.Source src = new Models.Source();
                                    src.title = str(so, "title");
                                    src.url = str(so, "url");
                                    src.snippet = str(so, "snippet");
                                    src.site = str(so, "site_name");
                                    src.age = str(so, "published_at");
                                    res.sources.add(src);
                                }
                                if (res.sources.isEmpty() && "failed".equals(str(call, "status"))) {
                                    res.failed = true;
                                }
                                int ri = idx[0]++;
                                l.onBlockStart(ri, res);
                                l.onBlockStop(ri);
                            }
                        } else {
                            Models.Block res = new Models.Block();
                            res.type = Models.B_FETCH_RESULT;
                            res.kind = "read_link";
                            res.id = use.id;
                            JSONObject page = call.optJSONObject("page");
                            if (page != null) {
                                res.url = str(page, "url");
                                res.fetchTitle = str(page, "title");
                            }
                            res.failed = "failed".equals(str(call, "status"));
                            int ri = idx[0]++;
                            l.onBlockStart(ri, res);
                            l.onBlockStop(ri);
                        }
                    }
                }
            }

            JSONArray ann = delta.optJSONArray("annotations");
            if (ann != null && idx[1] >= 0) {
                for (int i = 0; i < ann.length(); i++) {
                    JSONObject a = ann.optJSONObject(i);
                    if (a == null) continue;
                    JSONObject uc = a.optJSONObject("url_citation");
                    if (uc == null) continue;
                    Models.Citation c = new Models.Citation();
                    c.url = str(uc, "url");
                    c.title = str(uc, "title");
                    c.text = "";
                    l.onCitation(idx[1], c);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Usage openAiUsage(JSONObject u) {
        if (u == null) return null;
        Usage out = new Usage();
        out.inputTokens = u.optInt("prompt_tokens");
        out.outputTokens = u.optInt("completion_tokens");
        int hit = u.optInt("prompt_cache_hit_tokens", -1);
        int miss = u.optInt("prompt_cache_miss_tokens", -1);
        JSONObject details = u.optJSONObject("prompt_tokens_details");
        if (hit < 0 && details != null) hit = details.optInt("cached_tokens", 0);
        if (hit >= 0) out.cacheHit = hit;
        if (miss >= 0) out.cacheMiss = miss;
        else if (out.inputTokens > 0) out.cacheMiss = Math.max(0, out.inputTokens - out.cacheHit);
        if (out.inputTokens == 0 && out.outputTokens == 0) return null;
        return out;
    }

    // ------------------------------------------------------------------ 整包 JSON 降级

    private static void emitAnthropicWhole(String text, Listener l) throws ApiException {
        JSONObject o = parseOrThrow(text);
        JSONObject err = o.optJSONObject("error");
        if (err != null) {
            l.onError(new ApiException(ApiException.STREAM, err.optString("message", text), 0,
                    emptyToNull(err.optString("code"))));
            return;
        }
        l.onMessageStart(o.optString("id"), o.optString("model"));
        Usage u = anthropicUsage(o.optJSONObject("usage"));
        if (u != null) l.onUsage(u);
        JSONArray content = o.optJSONArray("content");
        int index = 0;
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                Models.Block b = readBlock(content.optJSONObject(i));
                if (b == null) continue;
                String full = b.text;
                b.text = "";
                l.onBlockStart(index, b);
                if (b.type == Models.B_TEXT && full != null && !full.isEmpty()) l.onTextDelta(index, full);
                if (b.type == Models.B_THINKING && full != null && !full.isEmpty()) l.onThinkingDelta(index, full);
                for (Models.Citation c : b.citations) l.onCitation(index, c);
                l.onBlockStop(index);
                index++;
            }
        }
        String wholeStop = o.optString("stop_reason", "end_turn");
        l.onMessageEnd(wholeStop, emptyToNull(o.optString("title")),
                "refusal".equals(wholeStop) ? "refusal" : null);
    }

    private static void emitOpenAiWhole(String text, Listener l) throws ApiException {
        JSONObject o = parseOrThrow(text);
        JSONObject err = o.optJSONObject("error");
        if (err != null) {
            l.onError(new ApiException(ApiException.STREAM, err.optString("message", text), 0));
            return;
        }
        l.onMessageStart(o.optString("id"), o.optString("model"));
        Usage u = openAiUsage(o.optJSONObject("usage"));
        if (u != null) l.onUsage(u);
        JSONArray choices = o.optJSONArray("choices");
        int index = 0;
        if (choices != null && choices.length() > 0) {
            JSONObject choice = choices.optJSONObject(0);
            JSONObject msg = choice == null ? null : choice.optJSONObject("message");
            if (msg != null) {
                String reasoning = str(msg, "reasoning_content");
                if (!reasoning.isEmpty()) {
                    Models.Block b = new Models.Block();
                    b.type = Models.B_THINKING;
                    b.startedAt = System.currentTimeMillis();
                    l.onBlockStart(index, b);
                    l.onThinkingDelta(index, reasoning);
                    l.onBlockStop(index);
                    index++;
                }
                String bodyText = str(msg, "content");
                Models.Block b = new Models.Block();
                b.type = Models.B_TEXT;
                l.onBlockStart(index, b);
                if (!bodyText.isEmpty()) l.onTextDelta(index, bodyText);
                l.onBlockStop(index);
            }
        }
        l.onMessageEnd("end_turn", null, null);
    }

    // ------------------------------------------------------------------ 消息序列化（前缀缓存复用）

    private static JSONObject anthropicTurn(Provider p, ChatRequest req, Turn t) throws Exception {
        if (Turn.LATEST_REMINDER.equals(t.role)) {
            return new JSONObject().put("role", "latest_reminder").put("content", t.text);
        }
        if (Turn.ASSISTANT.equals(t.role)) {
            if (t.rawJson != null && !t.rawJson.isEmpty()) {
                try {
                    return new JSONObject().put("role", "assistant")
                            .put("content", new JSONArray(t.rawJson));
                } catch (Exception ignored) {
                }
            }
            return new JSONObject().put("role", "assistant")
                    .put("content", new JSONObject().put("type", "text").put("text", t.text));
        }
        List<Models.FileRef> docs = usableDocs(t);
        List<Models.FileRef> files = req.vision ? usableImages(t) : new ArrayList<>();
        if (docs.isEmpty() && files.isEmpty()) {
            return new JSONObject().put("role", "user").put("content", t.text);
        }
        JSONArray content = new JSONArray();
        for (Models.FileRef d : docs) {
            content.put(new JSONObject().put("type", "document")
                    .put("source", new JSONObject().put("type", "file").put("file_id", d.serverId)));
        }
        for (Models.FileRef f : files) {
            JSONObject src = p.official() && f.serverId != null && !f.serverId.isEmpty()
                    ? new JSONObject().put("type", "file").put("file_id", f.serverId)
                    : new JSONObject().put("type", "base64")
                    .put("media_type", mediaType(f)).put("data", base64(f));
            content.put(new JSONObject().put("type", "image").put("source", src));
        }
        content.put(new JSONObject().put("type", "text").put("text", t.text));
        return new JSONObject().put("role", "user").put("content", content);
    }

    private static JSONArray anthropicMessages(Provider p, ChatRequest req, int count) throws Exception {
        JSONArray msgs = new JSONArray();
        for (int i = 0; i < count && i < req.turns.size(); i++) {
            msgs.put(anthropicTurn(p, req, req.turns.get(i)));
        }
        return msgs;
    }

    private static JSONObject openAiTurn(Provider p, ChatRequest req, Turn t) throws Exception {
        if (Turn.LATEST_REMINDER.equals(t.role)) {
            return new JSONObject().put("role", "latest_reminder").put("content", t.text);
        }
        if (Turn.ASSISTANT.equals(t.role)) {
            if (t.rawJson != null && !t.rawJson.isEmpty()) {
                try {
                    JSONObject raw = new JSONObject(t.rawJson);
                    raw.put("role", "assistant");
                    return raw;
                } catch (Exception ignored) {
                }
            }
            return new JSONObject().put("role", "assistant").put("content", t.text);
        }
        List<Models.FileRef> files = req.vision ? usableImages(t) : new ArrayList<>();
        if (files.isEmpty()) {
            return new JSONObject().put("role", "user").put("content", t.text);
        }
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", t.text));
        for (Models.FileRef f : files) {
            content.put(new JSONObject()
                    .put("type", "image_url")
                    .put("image_url", new JSONObject().put("url",
                            "data:" + mediaType(f) + ";base64," + base64(f))));
        }
        return new JSONObject().put("role", "user").put("content", content);
    }

    private static JSONArray openAiMessages(Provider p, ChatRequest req, int count) throws Exception {
        JSONArray msgs = new JSONArray();
        if (req.system != null && !req.system.isEmpty()) {
            msgs.put(new JSONObject().put("role", "system").put("content", req.system));
        }
        for (int i = 0; i < count && i < req.turns.size(); i++) {
            msgs.put(openAiTurn(p, req, req.turns.get(i)));
        }
        return msgs;
    }

    /** 除最后一轮之外的 messages JSON，用于写入本地前缀缓存。 */
    public static String prefixJson(Provider p, ChatRequest req) {
        try {
            int count = Math.max(0, req.turns.size() - 1);
            JSONArray msgs = Provider.PROTO_ANTHROPIC.equals(p.protocol)
                    ? anthropicMessages(p, req, count)
                    : openAiMessages(p, req, count);
            return msgs.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 模型 / 余额

    public static String listModels(Provider p, List<String> out) {
        String base = trimSlash(p.baseUrl);
        String[] candidates = base.endsWith("/v1")
                ? new String[]{base + "/models"}
                : new String[]{base + "/v1/models", base + "/models"};
        String lastError = null;
        for (String url : candidates) {
            HttpURLConnection conn = null;
            try {
                conn = open(url, p.apiKey, false);
                int code = conn.getResponseCode();
                if (code >= 400) {
                    lastError = readError(conn, code).getMessage();
                    continue;
                }
                JSONObject o = new JSONObject(readAll(decoded(conn)));
                JSONArray arr = o.optJSONArray("data");
                if (arr == null) arr = o.optJSONArray("models");
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    String id = null;
                    if (item instanceof JSONObject) {
                        id = ((JSONObject) item).optString("id");
                        if (id == null || id.isEmpty()) id = ((JSONObject) item).optString("name");
                    } else if (item != null) {
                        id = String.valueOf(item);
                    }
                    if (id != null && !id.isEmpty()) out.add(id);
                }
                if (!out.isEmpty()) return null;
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return lastError == null ? "未获取到模型列表" : lastError;
    }

    public static String balance(Provider p) {
        if (!p.official()) return null;
        HttpURLConnection conn = null;
        try {
            conn = open(trimSlash(p.baseUrl) + "/user/balance", p.apiKey, false);
            if (conn.getResponseCode() >= 400) return null;
            JSONObject o = new JSONObject(readAll(decoded(conn)));
            JSONArray infos = o.optJSONArray("balance_infos");
            if (infos == null || infos.length() == 0) return null;
            JSONObject first = infos.optJSONObject(0);
            if (first == null) return null;
            String cur = first.optString("currency", "CNY");
            String total = first.optString("total_balance", "");
            if (total.isEmpty()) return null;
            return ("CNY".equalsIgnoreCase(cur) ? "¥" : cur + " ") + total;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 余额明细：{总额, 赠送, 充值, 币种}；失败返回 null。 */
    public static String[] balanceDetail(Provider p) {
        if (!p.official()) return null;
        HttpURLConnection conn = null;
        try {
            conn = open(trimSlash(p.baseUrl) + "/user/balance", p.apiKey, false);
            if (conn.getResponseCode() >= 400) return null;
            JSONObject o = new JSONObject(readAll(decoded(conn)));
            JSONArray infos = o.optJSONArray("balance_infos");
            if (infos == null || infos.length() == 0) return null;
            JSONObject f = infos.optJSONObject(0);
            if (f == null) return null;
            return new String[]{f.optString("total_balance", ""), f.optString("granted_balance", ""),
                    f.optString("topped_up_balance", ""), f.optString("currency", "CNY")};
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static String verifyKey(Provider p) {
        List<String> models = new ArrayList<>();
        return listModels(p, models);
    }

    // ------------------------------------------------------------------ HTTP 基建

    private static HttpURLConnection open(String url, String apiKey, boolean post) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(post ? "POST" : "GET");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(300000);
        if (post) conn.setDoOutput(true);
        conn.setRequestProperty("Accept", post ? "text/event-stream, application/json" : "application/json");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("User-Agent", UA);
        if (apiKey != null && !apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("x-api-key", apiKey);
        }
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        return conn;
    }

    private static void writeBody(HttpURLConnection conn, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setFixedLengthStreamingMode(bytes.length);
        OutputStream os = conn.getOutputStream();
        os.write(bytes);
        os.flush();
        os.close();
    }

    private static InputStream decoded(HttpURLConnection conn) throws IOException {
        InputStream raw = conn.getInputStream();
        String enc = conn.getContentEncoding();
        if (enc == null) return raw;
        String e = enc.toLowerCase(java.util.Locale.US);
        if (e.contains("gzip")) return new GZIPInputStream(raw);
        return raw;
    }

    private static ApiException readError(HttpURLConnection conn, int code) {
        String body = "";
        try {
            InputStream es = conn.getErrorStream();
            if (es != null) body = readAll(es);
        } catch (Exception ignored) {
        }
        String message = body;
        String errCode = null;
        try {
            JSONObject err = new JSONObject(body).optJSONObject("error");
            if (err != null) {
                message = err.optString("message", body);
                errCode = emptyToNull(err.optString("code"));
            }
        } catch (Exception ignored) {
        }
        if (message == null || message.isEmpty()) message = "HTTP " + code;
        return new ApiException(ApiException.HTTP, message, code, errCode);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static JSONObject parse(String data) {
        if (data == null || data.isEmpty()) return null;
        try {
            return new JSONObject(data);
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject parseOrThrow(String text) throws ApiException {
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            throw new ApiException(ApiException.PROTOCOL, "无法解析响应：" + text, 0);
        }
    }

    /** org.json 会把 JSON null 读成字符串 "null"，这里统一归零，避免拼进正文。 */
    private static String str(JSONObject o, String key) {
        if (o == null) return "";
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return "";
        String s = String.valueOf(v);
        return "null".equals(s) ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String r = s.trim();
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    /** 非图片且已上传拿到 file_id 的附件按文档引用 */
    private static List<Models.FileRef> usableDocs(Turn t) {
        List<Models.FileRef> out = new ArrayList<>();
        for (Models.FileRef f : t.files) {
            if (f.image) continue;
            if (f.serverId == null || f.serverId.isEmpty()) continue;
            out.add(f);
        }
        return out;
    }

    private static List<Models.FileRef> usableImages(Turn t) {
        List<Models.FileRef> out = new ArrayList<>();
        for (Models.FileRef f : t.files) {
            if (f.image && f.localPath != null && !f.localPath.isEmpty()) out.add(f);
        }
        return out;
    }

    private static String mediaType(Models.FileRef f) {
        if (f.mime != null && f.mime.startsWith("image/")) return f.mime;
        return "image/png";
    }

    private static String base64(Models.FileRef f) {
        try {
            File file = new File(f.localPath);
            if (!file.exists()) return "";
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 内容块解析

    private static Models.Block readBlock(JSONObject o) {
        Models.Block b = readBlockInner(o);
        if (b != null && o != null) {
            b.raw = o.toString();
        }
        return b;
    }

    private static Models.Block readBlockInner(JSONObject o) {
        if (o == null) return null;
        String type = o.optString("type");
        Models.Block b = new Models.Block();
        if ("text".equals(type)) {
            b.type = Models.B_TEXT;
            b.text = o.optString("text");
            JSONArray cs = o.optJSONArray("citations");
            if (cs != null) {
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject c = cs.optJSONObject(i);
                    if (c == null) continue;
                    Models.Citation cit = new Models.Citation();
                    cit.url = c.optString("url");
                    cit.title = c.optString("title");
                    cit.text = c.optString("cited_text");
                    b.citations.add(cit);
                }
            }
            return b;
        }
        if ("thinking".equals(type)) {
            b.type = Models.B_THINKING;
            b.text = o.optString("thinking");
            b.signature = o.optString("signature");
            return b;
        }
        if ("server_tool_use".equals(type)) {
            b.type = Models.B_TOOL_USE;
            b.id = o.optString("id");
            b.name = o.optString("name");
            b.kind = o.optString("kind");
            JSONObject input = o.optJSONObject("input");
            if (input != null) {
                JSONArray qs = input.optJSONArray("queries");
                if (qs != null) for (int i = 0; i < qs.length(); i++) b.queries.add(qs.optString(i));
                b.url = input.optString("url");
            }
            if (b.kind.isEmpty()) b.kind = "search";
            return b;
        }
        if ("web_search_tool_result".equals(type)) {
            b.type = Models.B_SEARCH_RESULT;
            b.id = o.optString("tool_use_id");
            b.kind = o.optString("kind", "search");
            JSONArray content = o.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject item = content.optJSONObject(i);
                    if (item == null) continue;
                    if ("web_search_tool_result_error".equals(item.optString("type"))) {
                        b.failed = true;
                        b.note = item.optString("error_code", "unavailable");
                    } else if ("web_search_result".equals(item.optString("type"))) {
                        Models.Source s = new Models.Source();
                        s.title = item.optString("title");
                        s.url = item.optString("url");
                        s.snippet = item.optString("snippet");
                        s.site = item.optString("site_name");
                        s.age = item.optString("page_age");
                        b.sources.add(s);
                    }
                }
            }
            return b;
        }
        if ("web_fetch_tool_result".equals(type)) {
            b.type = Models.B_FETCH_RESULT;
            b.id = o.optString("tool_use_id");
            b.kind = o.optString("kind", "read_link");
            JSONObject content = o.optJSONObject("content");
            if (content != null) {
                if ("web_fetch_tool_result_error".equals(content.optString("type"))) {
                    b.failed = true;
                    b.note = content.optString("error_code", "unavailable");
                } else {
                    b.url = content.optString("url");
                    JSONObject inner = content.optJSONObject("content");
                    if (inner != null) b.fetchTitle = inner.optString("title");
                }
            }
            return b;
        }
        return null;
    }

    /** 上传附件（仅 Anthropic 兼容端点提供 Files API）。 */
    // ------------------------------------------------------------------ 语音工具（官方 /v1/tools）

    /** 整段语音合成：把文字转成 wav 写入 outFile；失败返回 null。 */
    public static String tts(Provider p, String text, String voiceId, File outFile,
                             AtomicBoolean cancelled) {
        if (text == null || text.trim().isEmpty()) return "empty";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(trimSlash(p.baseUrl) + "/v1/tools/tts").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + p.apiKey);
            conn.setRequestProperty("Accept", "audio/wav");
            conn.setRequestProperty("User-Agent", UA);
            JSONObject body = new JSONObject();
            body.put("text", text);
            body.put("voice_id", voiceId == null || voiceId.isEmpty() ? "stella" : voiceId);
            body.put("format", "wav");
            body.put("filter_markdown", true);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = conn.getResponseCode();
            if (code >= 400) {
                return "HTTP " + code + " " + readAll(conn.getErrorStream());
            }
            InputStream in = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(outFile);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                if (cancelled != null && cancelled.get()) {
                    in.close();
                    fos.close();
                    return "cancelled";
                }
                fos.write(buf, 0, n);
                total += n;
            }
            in.close();
            fos.close();
            return total > 64 ? null : "empty audio";
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 语音识别：上传 16 位 PCM WAV，返回转写文本；失败返回 null 并写 err。 */
    public static String asr(Provider p, File wav, String[] err) {
        String boundary = "----SereinAsr" + System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(trimSlash(p.baseUrl) + "/v1/tools/asr").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(180000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Authorization", "Bearer " + p.apiKey);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", UA);
            OutputStream os = conn.getOutputStream();
            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.write("Content-Type: audio/wav\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            FileInputStream fis = new FileInputStream(wav);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
            fis.close();
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = conn.getResponseCode();
            if (code >= 400) {
                if (err != null && err.length > 0) err[0] = "HTTP " + code + " " + readAll(conn.getErrorStream());
                return null;
            }
            JSONObject o = new JSONObject(readAll(decoded(conn)));
            return str(o, "text");
        } catch (Exception e) {
            if (err != null && err.length > 0) err[0] = String.valueOf(e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static final String PURPOSE_TEXT = "text_recognition";
    public static final String PURPOSE_LAYOUT = "rich_layout_recognition";
    public static final String PURPOSE_USER_DATA = "user_data";

    public static String uploadFile(Provider p, File file, String mime, String purpose,
                                    AtomicBoolean cancelled) throws ApiException {
        String boundary = "----DeepSeekBoundary" + System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            String url = trimSlash(p.baseUrl) + "/anthropic/v1/files";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(300000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("x-api-key", p.apiKey);
            conn.setRequestProperty("anthropic-beta", FILES_BETA);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", UA);

            OutputStream os = conn.getOutputStream();
            boolean isImage = mime != null && mime.startsWith("image/");
            if (!isImage) {
                // purpose 必须出现在 file 字段之前
                writeField(os, boundary, "purpose",
                        purpose == null || purpose.isEmpty() ? PURPOSE_TEXT : purpose);
            }
            String safeName = file.getName().replace("\"", "_");
            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Type: " + (mime == null || mime.isEmpty() ? "application/octet-stream" : mime) + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            FileInputStream fis = new FileInputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                if (cancelled != null && cancelled.get()) {
                    fis.close();
                    os.close();
                    throw new ApiException(ApiException.NETWORK, "已取消", 0);
                }
                os.write(buf, 0, n);
            }
            fis.close();
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code >= 400) throw readError(conn, code);
            String id = new JSONObject(readAll(conn.getInputStream())).optString("id");
            if (id.isEmpty()) throw new ApiException(ApiException.PROTOCOL, "上传响应缺少 file id", code);
            return id;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ApiException.NETWORK,
                    e.getMessage() == null ? e.toString() : e.getMessage(), 0);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void writeField(OutputStream os, String boundary, String name, String value) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}
