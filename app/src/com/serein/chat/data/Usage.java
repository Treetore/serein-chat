package com.serein.chat.data;

import org.json.JSONObject;

/** 单轮用量：token 数、缓存命中、耗时与生成速度。 */
public final class Usage {

    public int inputTokens;
    public int outputTokens;
    /** 命中前缀缓存的输入 token */
    public int cacheHit;
    /** 未命中缓存的输入 token */
    public int cacheMiss;
    public long elapsedMs;

    public boolean hasCache() {
        return cacheHit + cacheMiss > 0;
    }

    public double tokensPerSecond() {
        if (elapsedMs <= 0 || outputTokens <= 0) return 0;
        return outputTokens * 1000.0 / elapsedMs;
    }

    public double cacheRate() {
        int total = cacheHit + cacheMiss;
        return total <= 0 ? 0 : cacheHit * 100.0 / total;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("in", inputTokens);
            o.put("out", outputTokens);
            o.put("ch", cacheHit);
            o.put("cm", cacheMiss);
            o.put("ms", elapsedMs);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Usage from(JSONObject o) {
        if (o == null) return null;
        Usage u = new Usage();
        u.inputTokens = o.optInt("in");
        u.outputTokens = o.optInt("out");
        u.cacheHit = o.optInt("ch");
        u.cacheMiss = o.optInt("cm");
        u.elapsedMs = o.optLong("ms");
        return u;
    }
}
