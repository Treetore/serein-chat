package com.serein.chat.data;

import android.content.Context;
import android.content.SharedPreferences;

/** 本机设置：API Key、模型、深度思考与智能搜索开关、主题。对应 chat-demo 的 settings.ts。 */
public final class Prefs {

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    public static final String MODEL_FLASH = "deepseek-flash";
    public static final String MODEL_PRO = "deepseek-v4-pro";

    private static final String FILE = "deepseek.prefs";
    private static final String K_API_KEY = "api-key";
    private static final String K_MODEL = "model";
    private static final String K_THINKING = "thinking";
    private static final String K_SEARCH = "search";
    private static final String K_THEME = "theme";
    private static final String K_BASE_URL = "base-url";
    private static final String K_LOCALE = "locale";
    private static final String K_PROVIDERS = "providers";
    private static final String K_ACTIVE_PROVIDER = "active-provider";
    private static final String K_ACCENT = "accent";
    private static final String K_BG_IMAGE = "bg-image";
    private static final String K_BG_DIM = "bg-dim";
    private static final String K_THINKING_LEVEL = "thinking-level";
    private static final String K_LOCATION = "user-location";

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String apiKey() {
        String v = sp.getString(K_API_KEY, null);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    public void setApiKey(String key) {
        sp.edit().putString(K_API_KEY, key == null ? "" : key.trim()).apply();
    }

    public boolean hasApiKey() {
        return apiKey() != null;
    }

    public String baseUrl() {
        String v = sp.getString(K_BASE_URL, DEFAULT_BASE_URL);
        return v == null || v.trim().isEmpty() ? DEFAULT_BASE_URL : v.trim();
    }

    public void setBaseUrl(String url) {
        sp.edit().putString(K_BASE_URL, url == null ? "" : url.trim()).apply();
    }

    public String model() {
        String v = sp.getString(K_MODEL, MODEL_FLASH);
        return MODEL_PRO.equals(v) ? MODEL_PRO : MODEL_FLASH;
    }

    public void setModel(String model) {
        sp.edit().putString(K_MODEL, MODEL_PRO.equals(model) ? MODEL_PRO : MODEL_FLASH).apply();
    }

    public boolean thinking() {
        return sp.getBoolean(K_THINKING, false);
    }

    public void setThinking(boolean on) {
        sp.edit().putBoolean(K_THINKING, on).apply();
    }

    public boolean search() {
        return sp.getBoolean(K_SEARCH, false);
    }

    public void setSearch(boolean on) {
        sp.edit().putBoolean(K_SEARCH, on).apply();
    }

    /** 按会话保存的输入草稿（会话 id 为 null 时用 "new"）。 */
    public String draft(String sessionId) {
        return sp.getString("draft:" + (sessionId == null ? "new" : sessionId), "");
    }

    public void setDraft(String sessionId, String text) {
        String key = "draft:" + (sessionId == null ? "new" : sessionId);
        if (text == null || text.trim().isEmpty()) {
            sp.edit().remove(key).apply();
        } else {
            sp.edit().putString(key, text).apply();
        }
    }

    public void clearDraft(String sessionId) {
        sp.edit().remove("draft:" + (sessionId == null ? "new" : sessionId)).apply();
    }

    /** 界面语言：zh / en，未设置时跟随系统。 */
    public String locale() {
        String v = sp.getString(K_LOCALE, null);
        if ("zh".equals(v) || "en".equals(v)) return v;
        return java.util.Locale.getDefault().getLanguage().startsWith("zh") ? "zh" : "en";
    }

    public void setLocale(String lang) {
        sp.edit().putString(K_LOCALE, "en".equals(lang) ? "en" : "zh").apply();
    }

    // ---------------------------------------------------------------- 服务商

    /** 全部服务商；首次使用时给出内置的 DeepSeek 条目。 */
    public java.util.List<Provider> providers() {
        java.util.List<Provider> list = new java.util.ArrayList<>();
        String raw = SecureStore.decrypt(sp.getString(K_PROVIDERS, null));
        if (raw != null) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.optJSONObject(i);
                    if (o != null) list.add(Provider.from(o));
                }
            } catch (Exception ignored) {
            }
        }
        if (list.isEmpty()) list.add(Provider.deepseekDefault());
        return list;
    }

    public void saveProviders(java.util.List<Provider> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (Provider p : list) arr.put(p.toJson());
        sp.edit().putString(K_PROVIDERS, SecureStore.encrypt(arr.toString())).apply();
    }

    public String activeProviderId() {
        String v = sp.getString(K_ACTIVE_PROVIDER, null);
        if (v == null || v.isEmpty()) {
            java.util.List<Provider> all = providers();
            return all.isEmpty() ? "deepseek" : all.get(0).id;
        }
        return v;
    }

    public void setActiveProviderId(String id) {
        sp.edit().putString(K_ACTIVE_PROVIDER, id).apply();
    }

    public Provider activeProvider() {
        java.util.List<Provider> all = providers();
        String id = activeProviderId();
        for (Provider p : all) {
            if (p.id.equals(id)) return p;
        }
        return all.isEmpty() ? Provider.deepseekDefault() : all.get(0);
    }

    public void updateProvider(Provider p) {
        java.util.List<Provider> all = providers();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(p.id)) {
                all.set(i, p);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(p);
        saveProviders(all);
    }

    public void deleteProvider(String id) {
        java.util.List<Provider> all = providers();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                all.remove(i);
                break;
            }
        }
        saveProviders(all);
        if (id.equals(activeProviderId()) && !all.isEmpty()) {
            setActiveProviderId(all.get(0).id);
        }
    }

    // ---------------------------------------------------------------- 外观

    /** 自定义强调色；0 表示使用默认品牌色。 */
    public int accent() {
        return sp.getInt(K_ACCENT, 0);
    }

    public void setAccent(int color) {
        sp.edit().putInt(K_ACCENT, color).apply();
    }

    public String backgroundImage() {
        return sp.getString(K_BG_IMAGE, "");
    }

    public void setBackgroundImage(String uri) {
        sp.edit().putString(K_BG_IMAGE, uri == null ? "" : uri).apply();
    }

    /** 背景图暗化程度（0-80）。 */
    public int backgroundDim() {
        return sp.getInt(K_BG_DIM, 35);
    }

    public void setBackgroundDim(int v) {
        sp.edit().putInt(K_BG_DIM, Math.max(0, Math.min(80, v))).apply();
    }

    /** 用户所在地（用于 latest_reminder，可为空）。 */
    public String location() {
        return sp.getString(K_LOCATION, "");
    }

    public void setLocation(String v) {
        sp.edit().putString(K_LOCATION, v == null ? "" : v.trim()).apply();
    }

    /** 思考强度：0 低 / 1 中 / 2 高。 */
    public int thinkingLevel() {
        return Math.max(0, Math.min(2, sp.getInt(K_THINKING_LEVEL, 1)));
    }

    public void setThinkingLevel(int level) {
        sp.edit().putInt(K_THINKING_LEVEL, Math.max(0, Math.min(2, level))).apply();
    }

    public int theme() {
        return sp.getInt(K_THEME, THEME_SYSTEM);
    }

    public void setTheme(int t) {
        sp.edit().putInt(K_THEME, t).apply();
    }
}
