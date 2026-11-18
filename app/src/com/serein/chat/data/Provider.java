package com.serein.chat.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 第三方服务商：协议、地址、密钥、模型清单与各模型的多模态能力。 */
public final class Provider {

    public static final String PROTO_OPENAI = "openai";
    public static final String PROTO_ANTHROPIC = "anthropic";

    public String id = "";
    public String name = "";
    /** openai（/chat/completions 兼容）或 anthropic（/v1/messages 兼容） */
    public String protocol = PROTO_OPENAI;
    public String baseUrl = "";
    public String apiKey = "";
    /** DeepSeek 官方：展示余额与缓存命中，请求走 Anthropic 兼容 Messages */
    public boolean deepseek;
    public String model = "";
    public final List<ModelInfo> models = new ArrayList<>();

    /** 是否按 DeepSeek 官方处理：余额、联网搜索、标题、思考强度都以此为准。 */
    public boolean official() {
        if (deepseek) return true;
        String n = name == null ? "" : name.toLowerCase();
        String u = baseUrl == null ? "" : baseUrl.toLowerCase();
        return n.contains("deepseek") || u.contains("deepseek");
    }

    public ModelInfo modelInfo(String id) {
        for (ModelInfo m : models) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }

    public ModelInfo ensureModel(String id) {
        ModelInfo m = modelInfo(id);
        if (m == null) {
            m = new ModelInfo();
            m.id = id;
            models.add(m);
        }
        return m;
    }

    /** 单个模型的能力；未探测过的按保守假设（无视觉）。 */
    public static final class ModelInfo {
        public String id = "";
        public boolean vision;
        public boolean tools = true;
        public boolean thinking;
        /** auto：由 /models 与命名规则推断；manual：用户指定 */
        public String source = "auto";

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("v", vision);
                o.put("t", tools);
                o.put("k", thinking);
                o.put("s", source);
            } catch (Exception ignored) {
            }
            return o;
        }

        static ModelInfo from(JSONObject o) {
            ModelInfo m = new ModelInfo();
            m.id = o.optString("id");
            m.vision = o.optBoolean("v");
            m.tools = o.optBoolean("t", true);
            m.thinking = o.optBoolean("k");
            m.source = o.optString("s", "auto");
            return m;
        }
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("proto", protocol);
            o.put("base", baseUrl);
            o.put("key", apiKey);
            o.put("ds", deepseek);
            o.put("model", model);
            JSONArray arr = new JSONArray();
            for (ModelInfo m : models) arr.put(m.toJson());
            o.put("models", arr);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Provider from(JSONObject o) {
        Provider p = new Provider();
        p.id = o.optString("id");
        p.name = o.optString("name");
        p.protocol = o.optString("proto", PROTO_OPENAI);
        p.baseUrl = o.optString("base");
        p.apiKey = o.optString("key");
        p.deepseek = o.optBoolean("ds");
        p.model = o.optString("model");
        JSONArray arr = o.optJSONArray("models");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m != null) p.models.add(ModelInfo.from(m));
            }
        }
        return p;
    }

    /** 内置的 DeepSeek 官方服务商。 */
    public static Provider deepseekDefault() {
        Provider p = new Provider();
        p.id = "deepseek";
        p.name = "DeepSeek";
        p.protocol = PROTO_ANTHROPIC;
        p.baseUrl = "https://api.deepseek.com";
        p.deepseek = true;
        p.model = "deepseek-flash";
        ModelInfo a = new ModelInfo();
        a.id = "deepseek-flash";
        a.vision = true;
        a.thinking = true;
        a.tools = true;
        a.source = "manual";
        ModelInfo b = new ModelInfo();
        b.id = "deepseek-v4-pro";
        b.thinking = true;
        b.tools = true;
        b.source = "manual";
        p.models.add(a);
        p.models.add(b);
        return p;
    }
}
