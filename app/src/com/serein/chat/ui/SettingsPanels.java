package com.serein.chat.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.serein.chat.R;
import com.serein.chat.api.Api;
import com.serein.chat.data.Prefs;
import com.serein.chat.data.Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 服务商管理、模型能力、外观自定义三类子面板。 */
public final class SettingsPanels {

    private SettingsPanels() {
    }

    // ------------------------------------------------------------------ 服务商列表

    public static void providers(Activity a, Theme t, FrameLayout root, Prefs prefs, Runnable changed) {
        Context c = a;
        LinearLayout card = Sheets.sheetCard(c, t);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);

        List<Provider> all = prefs.providers();
        String activeId = prefs.activeProviderId();
        for (Provider p : all) {
            LinearLayout row = Ui.row(c);
            row.setPadding(Ui.dp(18), Ui.dp(11), Ui.dp(10), Ui.dp(11));
            Ui.pressable(row, Ui.round(0x00000000, 10), 10, t.hoverAccent);

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.text(c, p.name, 14, t.labelPrimary);
            TextView meta = Ui.text(c, (Provider.PROTO_ANTHROPIC.equals(p.protocol) ? "Anthropic" : "OpenAI")
                    + " · " + host(p.baseUrl), 11, t.labelTertiary);
            texts.addView(name);
            texts.addView(meta);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (p.id.equals(activeId)) {
                row.addView(Ui.icon(c, R.drawable.ic_check, t.brandText, 18));
            }
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34));
            ilp.leftMargin = Ui.dp(2);
            android.widget.ImageView edit = Ui.iconButton(c, R.drawable.ic_edit, t.labelSecondary, t);
            edit.setOnClickListener(v -> editProvider(a, t, prefs, p, changed));
            row.addView(edit, ilp);

            row.setOnClickListener(v -> {
                prefs.setActiveProviderId(p.id);
                changed.run();
                Ui.toast(c, c.getString(R.string.provider_switched, p.name));
            });
            body.addView(row);
        }

        TextView add = Ui.text(c, "+ " + c.getString(R.string.add_provider), 14, t.brandText);
        add.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
        Ui.pressable(add, Ui.round(t.ghostActiveFill, 10), 10, t.hoverAccent);
        add.setOnClickListener(v -> editProvider(a, t, prefs, null, changed));
        body.addView(add);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body);
        sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(Ui.dp(480), (int) (c.getResources().getDisplayMetrics().heightPixels * 0.6f))));

        Runnable[] closeRef = new Runnable[1];
        Sheets.header(c, t, card, c.getString(R.string.providers), () -> {
            if (closeRef[0] != null) closeRef[0].run();
        });
        card.addView(sv);
        closeRef[0] = Sheets.showBottom(root, t, card, null);
    }

    /** 编辑所在地（latest_reminder 的第 3 段，可留空）。 */
    public static void editLocation(Activity a, Theme t, Prefs prefs, Runnable changed) {
        Context c = a;
        EditText et = new EditText(c);
        et.setText(prefs.location());
        et.setHint(c.getString(R.string.location_hint));
        et.setSingleLine(true);
        et.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), Ui.dp(12));
        new AlertDialog.Builder(c)
                .setTitle(R.string.settings_location)
                .setView(et)
                .setPositiveButton(R.string.save, (d, w) -> {
                    prefs.setLocation(et.getText().toString());
                    changed.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void openUrl(Context c, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            Ui.toast(c, c.getString(R.string.open_link_failed));
        }
    }

    private static String host(String url) {
        if (url == null) return "";
        return url.replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    // ------------------------------------------------------------------ 编辑服务商

    public static void editProvider(Activity a, Theme t, Prefs prefs, Provider existing,
                                    Runnable changed) {
        Context c = a;
        Provider draft = existing != null ? Provider.from(existing.toJson()) : new Provider();
        if (existing == null) {
            draft.id = UUID.randomUUID().toString();
            draft.name = "";
            draft.protocol = Provider.PROTO_OPENAI;
            draft.baseUrl = "https://";
        }

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(18);
        box.setPadding(pad, Ui.dp(6), pad, 0);

        EditText name = field(c, t, draft.name, c.getString(R.string.provider_name));
        box.addView(label(c, t, c.getString(R.string.provider_name)));
        box.addView(name);

        box.addView(label(c, t, c.getString(R.string.protocol)));
        LinearLayout protoRow = Ui.row(c);
        TextView openai = Sheets.themeChip(c, t, "OpenAI", Provider.PROTO_OPENAI.equals(draft.protocol));
        TextView anthropic = Sheets.themeChip(c, t, "Anthropic", Provider.PROTO_ANTHROPIC.equals(draft.protocol));
        final String[] protocol = {draft.protocol};
        openai.setOnClickListener(v -> {
            protocol[0] = Provider.PROTO_OPENAI;
            Sheets.chipSelect(openai, anthropic, true, t);
        });
        anthropic.setOnClickListener(v -> {
            protocol[0] = Provider.PROTO_ANTHROPIC;
            Sheets.chipSelect(openai, anthropic, false, t);
        });
        LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prlp.rightMargin = Ui.dp(8);
        protoRow.addView(openai, prlp);
        protoRow.addView(anthropic);
        box.addView(protoRow);

        box.addView(label(c, t, c.getString(R.string.base_url)));
        EditText base = field(c, t, draft.baseUrl, "https://api.openai.com");
        box.addView(base);

        box.addView(label(c, t, c.getString(R.string.api_key_title)));
        EditText key = field(c, t, draft.apiKey, c.getString(R.string.api_key_placeholder));
        box.addView(key);

        box.addView(label(c, t, c.getString(R.string.model)));
        EditText model = field(c, t, draft.model, "gpt-4o-mini");
        box.addView(model);

        TextView fetch = Ui.text(c, c.getString(R.string.fetch_models), 14, t.brandText);
        fetch.setPadding(0, Ui.dp(10), 0, Ui.dp(4));
        box.addView(fetch);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(box);

        AlertDialog dlg = new AlertDialog.Builder(c)
                .setTitle(existing == null ? R.string.add_provider : R.string.edit_provider)
                .setView(sv)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.delete, null)
                .create();
        dlg.setOnShowListener(d -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String n = name.getText().toString().trim();
                String b = base.getText().toString().trim();
                if (n.isEmpty() || b.isEmpty()) {
                    Ui.toast(c, c.getString(R.string.provider_required));
                    return;
                }
                draft.name = n;
                draft.protocol = protocol[0];
                draft.baseUrl = b;
                draft.apiKey = key.getText().toString().trim();
                draft.model = model.getText().toString().trim();
                // 名称或域名含 deepseek 即按官方处理（余额、联网搜索、标题生成）
                draft.deepseek = draft.baseUrl.contains("api.deepseek.com")
                        || n.toLowerCase().contains("deepseek");
                if (draft.model.isEmpty() && !draft.models.isEmpty()) draft.model = draft.models.get(0).id;
                if (draft.modelInfo(draft.model) == null && !draft.model.isEmpty()) {
                    Provider.ModelInfo detected = detect(draft.model);
                    draft.models.add(detected);
                }
                prefs.updateProvider(draft);
                prefs.setActiveProviderId(draft.id);
                changed.run();
                dlg.dismiss();
                Ui.toast(c, c.getString(R.string.provider_saved, n));
            });
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                prefs.deleteProvider(draft.id);
                changed.run();
                dlg.dismiss();
            });
            if (existing == null) dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
        });
        fetch.setOnClickListener(v -> {
            Provider probe = Provider.from(draft.toJson());
            probe.protocol = protocol[0];
            probe.baseUrl = base.getText().toString().trim();
            probe.apiKey = key.getText().toString().trim();
            if (probe.baseUrl.isEmpty()) {
                Ui.toast(c, c.getString(R.string.provider_required));
                return;
            }
            Ui.toast(c, c.getString(R.string.fetching_models));
            new Thread(() -> {
                List<String> ids = new ArrayList<>();
                String err = Api.listModels(probe, ids);
                a.runOnUiThread(() -> {
                    if (err != null) {
                        Ui.toast(c, c.getString(R.string.fetch_models_failed, err));
                        return;
                    }
                    draft.models.clear();
                    for (String id : ids) draft.models.add(detect(id));
                    if (draft.model.isEmpty()) draft.model = ids.get(0);
                    model.setText(draft.model);
                    Ui.toast(c, c.getString(R.string.models_fetched, ids.size()));
                });
            }).start();
        });
        dlg.show();
    }

    /** 由模型命名推断多模态能力；推断不出则留给用户手动设定。 */
    public static Provider.ModelInfo detect(String id) {
        Provider.ModelInfo m = new Provider.ModelInfo();
        m.id = id;
        String s = id.toLowerCase(Locale.US);
        m.vision = containsAny(s, "vl", "vision", "-4o", "4o-", "omni", "gemini", "claude-3",
                "claude-4", "llava", "pixtral", "glm-4v", "internvl", "qwen-vl", "minicpm-v",
                "grok-2-vision", "step-1v", "moonshot-v1-vision");
        m.tools = !containsAny(s, "embed", "whisper", "tts", "rerank", "moderation", "-audio");
        m.thinking = containsAny(s, "r1", "reasoner", "thinking", "qwq", "-o1", "o3-", "o4-",
                "deepseek-reason", "glm-z1", "reasoning");
        m.source = "auto";
        return m;
    }

    private static boolean containsAny(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    // ------------------------------------------------------------------ 模型与能力

    public static void models(Activity a, Theme t, FrameLayout root, Prefs prefs, Runnable changed) {
        Context c = a;
        Provider p = prefs.activeProvider();
        LinearLayout card = Sheets.sheetCard(c, t);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);

        TextView hint = Ui.text(c, p.name + " · " + p.models.size() + " " + c.getString(R.string.models_count),
                12, t.labelTertiary);
        hint.setPadding(Ui.dp(18), Ui.dp(4), Ui.dp(18), Ui.dp(8));
        body.addView(hint);

        for (Provider.ModelInfo m : p.models) {
            LinearLayout row = Ui.row(c);
            row.setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(10));
            Ui.pressable(row, Ui.round(0x00000000, 10), 10, t.hoverAccent);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Ui.text(c, m.id, 14, m.id.equals(p.model) ? t.brandText : t.labelPrimary));
            col.addView(Ui.text(c, caps(c, m), 11, t.labelTertiary));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (m.id.equals(p.model)) row.addView(Ui.icon(c, R.drawable.ic_check, t.brandText, 18));
            row.setOnClickListener(v -> {
                p.model = m.id;
                prefs.updateProvider(p);
                changed.run();
                capability(a, t, prefs, p, m.id, changed);
            });
            body.addView(row);
        }

        TextView add = Ui.text(c, "+ " + c.getString(R.string.add_model_manual), 14, t.brandText);
        add.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
        Ui.pressable(add, Ui.round(t.ghostActiveFill, 10), 10, t.hoverAccent);
        add.setOnClickListener(v -> manualModel(a, t, prefs, p, changed));
        body.addView(add);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body);
        sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(Ui.dp(460), (int) (c.getResources().getDisplayMetrics().heightPixels * 0.6f))));

        Runnable[] closeRef = new Runnable[1];
        Sheets.header(c, t, card, c.getString(R.string.model), () -> {
            if (closeRef[0] != null) closeRef[0].run();
        });
        card.addView(sv);
        closeRef[0] = Sheets.showBottom(root, t, card, null);
    }

    private static String caps(Context c, Provider.ModelInfo m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.vision ? c.getString(R.string.cap_vision) : c.getString(R.string.cap_no_vision));
        sb.append(" · ").append(m.tools ? c.getString(R.string.cap_tools) : c.getString(R.string.cap_no_tools));
        if (m.thinking) sb.append(" · ").append(c.getString(R.string.cap_thinking));
        sb.append(" · ").append("manual".equals(m.source)
                ? c.getString(R.string.cap_manual) : c.getString(R.string.cap_auto));
        return sb.toString();
    }

    private static void manualModel(Activity a, Theme t, Prefs prefs, Provider p, Runnable changed) {
        EditText et = new EditText(a);
        et.setHint("model-id");
        et.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), Ui.dp(12));
        new AlertDialog.Builder(a)
                .setTitle(R.string.add_model_manual)
                .setView(et)
                .setPositiveButton(R.string.confirm, (d, w) -> {
                    String id = et.getText().toString().trim();
                    if (id.isEmpty()) return;
                    p.models.add(detect(id));
                    p.model = id;
                    prefs.updateProvider(p);
                    changed.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 手动设定某个模型的能力范围。 */
    public static void capability(Activity a, Theme t, Prefs prefs, Provider p, String modelId,
                                  Runnable changed) {
        Context c = a;
        Provider.ModelInfo m = p.ensureModel(modelId);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), 0);

        TextView auto = Ui.text(c, c.getString(R.string.cap_detected, caps(c, m)), 12, t.labelTertiary);
        box.addView(auto);
        TextView note = Ui.text(c, c.getString(R.string.cap_server_params), 11, t.labelCaption);
        note.setPadding(0, Ui.dp(6), 0, Ui.dp(2));
        box.addView(note);

        CheckBox vision = check(c, t, c.getString(R.string.cap_vision), m.vision);
        CheckBox tools = check(c, t, c.getString(R.string.cap_tools), m.tools);
        CheckBox thinking = check(c, t, c.getString(R.string.cap_thinking), m.thinking);
        box.addView(vision);
        box.addView(tools);
        box.addView(thinking);

        new AlertDialog.Builder(c)
                .setTitle(modelId)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    m.vision = vision.isChecked();
                    m.tools = tools.isChecked();
                    m.thinking = thinking.isChecked();
                    m.source = "manual";
                    prefs.updateProvider(p);
                    changed.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static CheckBox check(Context c, Theme t, String label, boolean checked) {
        CheckBox cb = new CheckBox(c);
        cb.setText(label);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cb.setTextColor(t.labelPrimary);
        cb.setChecked(checked);
        cb.setPadding(0, Ui.dp(4), 0, Ui.dp(4));
        return cb;
    }

    // ------------------------------------------------------------------ 外观

    public static void appearance(Activity a, Theme t, FrameLayout root, Prefs prefs, Runnable changed) {
        Context c = a;
        LinearLayout card = Sheets.sheetCard(c, t);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);

        body.addView(Sheets.sectionTitle(c, t, c.getString(R.string.theme)));
        LinearLayout themeRow = Ui.row(c);
        themeRow.setPadding(Ui.dp(18), 0, Ui.dp(18), Ui.dp(6));
        int cur = prefs.theme();
        TextView light = Sheets.themeChip(c, t, c.getString(R.string.theme_light), cur == Prefs.THEME_LIGHT);
        TextView dark = Sheets.themeChip(c, t, c.getString(R.string.theme_dark), cur == Prefs.THEME_DARK);
        TextView system = Sheets.themeChip(c, t, c.getString(R.string.theme_system), cur == Prefs.THEME_SYSTEM);
        light.setOnClickListener(v -> {
            prefs.setTheme(Prefs.THEME_LIGHT);
            changed.run();
        });
        dark.setOnClickListener(v -> {
            prefs.setTheme(Prefs.THEME_DARK);
            changed.run();
        });
        system.setOnClickListener(v -> {
            prefs.setTheme(Prefs.THEME_SYSTEM);
            changed.run();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = Ui.dp(8);
        themeRow.addView(light, clp);
        themeRow.addView(dark, new LinearLayout.LayoutParams(clp));
        ((LinearLayout.LayoutParams) dark.getLayoutParams()).rightMargin = Ui.dp(8);
        themeRow.addView(system);
        body.addView(themeRow);

        body.addView(Sheets.sectionTitle(c, t, c.getString(R.string.accent_color)));
        LinearLayout swatches = Ui.row(c);
        swatches.setPadding(Ui.dp(18), 0, Ui.dp(18), Ui.dp(10));
        int[] colors = {0, 0xFF3964FE, 0xFF7C4DFF, 0xFF00897B, 0xFFF57C00, 0xFFE53935, 0xFF00838F};
        for (int color : colors) {
            View dot = new View(c);
            int size = Ui.dp(30);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(size, size);
            dlp.rightMargin = Ui.dp(10);
            dot.setLayoutParams(dlp);
            if (color == 0) {
                dot.setBackground(Ui.roundStroke(t.bgLayer2, t.borderL2, 1, 15));
                TextView none = Ui.text(c, "A", 12, t.labelSecondary);
                none.setGravity(Gravity.CENTER);
                dot = none;
                dot.setLayoutParams(dlp);
                dot.setBackground(Ui.roundStroke(t.bgLayer2, t.borderL2, 1, 15));
            } else {
                dot.setBackground(Ui.circle(color));
            }
            if (prefs.accent() == color) {
                dot.setScaleX(1.12f);
                dot.setScaleY(1.12f);
                dot.setElevation(Ui.dp(3));
            }
            dot.setOnClickListener(v -> {
                prefs.setAccent(color);
                changed.run();
            });
            swatches.addView(dot);
        }
        body.addView(swatches);

        body.addView(Sheets.sectionTitle(c, t, c.getString(R.string.bg_image)));
        LinearLayout bgRow = Ui.row(c);
        bgRow.setPadding(Ui.dp(18), 0, Ui.dp(18), Ui.dp(6));
        TextView pick = Sheets.themeChip(c, t, c.getString(R.string.bg_pick), false);
        TextView clear = Sheets.themeChip(c, t, c.getString(R.string.bg_clear), false);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                a.startActivityForResult(i, com.serein.chat.MainActivity.REQ_BG);
            } catch (Exception e) {
                Ui.toast(c, c.getString(R.string.picker_unavailable));
            }
        });
        clear.setOnClickListener(v -> {
            prefs.setBackgroundImage("");
            changed.run();
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = Ui.dp(8);
        bgRow.addView(pick, blp);
        bgRow.addView(clear);
        body.addView(bgRow);

        body.addView(Sheets.sectionTitle(c, t, c.getString(R.string.bg_dim)));
        SeekBar dim = new SeekBar(c);
        dim.setMax(80);
        dim.setProgress(prefs.backgroundDim());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = Ui.dp(18);
        dlp.rightMargin = Ui.dp(18);
        dim.setLayoutParams(dlp);
        dim.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.setBackgroundDim(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                changed.run();
            }
        });
        body.addView(dim);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body);
        sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(Ui.dp(480), (int) (c.getResources().getDisplayMetrics().heightPixels * 0.62f))));

        Runnable[] closeRef = new Runnable[1];
        Sheets.header(c, t, card, c.getString(R.string.appearance), () -> {
            if (closeRef[0] != null) closeRef[0].run();
        });
        card.addView(sv);
        closeRef[0] = Sheets.showBottom(root, t, card, null);
    }

    // ------------------------------------------------------------------ 余额与用量

    /** 居中面板：余额明细 + 近 7 天用量聚合。 */
    public static void balance(Activity a, Theme t, FrameLayout root, Prefs prefs,
                               com.serein.chat.data.Db db, Provider p, Runnable changed) {
        Context c = a;
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(t.bgLayer2);
        bg.setCornerRadius(Ui.dp(18));
        card.setBackground(bg);

        Runnable[] closeRef = new Runnable[1];
        Sheets.header(c, t, card, c.getString(R.string.balance_title), () -> {
            if (closeRef[0] != null) closeRef[0].run();
        });

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);

        // 余额区（先占位，异步填充）
        TextView total = Ui.text(c, "—", 26, t.labelPrimary);
        total.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        total.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), Ui.dp(2));
        body.addView(total);
        TextView sub = Ui.text(c, c.getString(R.string.balance_total), 12, t.labelTertiary);
        sub.setPadding(Ui.dp(20), 0, Ui.dp(20), Ui.dp(8));
        body.addView(sub);
        TextView detail = Ui.text(c, "", 12, t.labelTertiary);
        detail.setPadding(Ui.dp(20), 0, Ui.dp(20), Ui.dp(12));
        detail.setLineSpacing(Ui.dp(3), 1f);
        body.addView(detail);

        // 累计用量（P2-3：缓存节省可视化）
        long[] totals = db.usageTotals();
        if (totals[0] > 0) {
            TextView sum = Ui.text(c, c.getString(R.string.usage_total,
                    (int) totals[0], compact(totals[1]), compact(totals[2]), compact(totals[3])),
                    12, t.labelSecondary);
            sum.setPadding(Ui.dp(20), Ui.dp(2), Ui.dp(20), Ui.dp(10));
            body.addView(sum);
        }

        // 官方平台快捷入口：充值 / 用量
        LinearLayout links = Ui.row(c);
        links.setPadding(Ui.dp(20), 0, Ui.dp(20), Ui.dp(12));
        TextView topUp = Sheets.themeChip(c, t, c.getString(R.string.top_up), true);
        TextView usage = Sheets.themeChip(c, t, c.getString(R.string.usage_query), false);
        topUp.setOnClickListener(v -> openUrl(c, "https://platform.deepseek.com/top_up"));
        usage.setOnClickListener(v -> openUrl(c, "https://platform.deepseek.com/usage"));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l1.rightMargin = Ui.dp(8);
        links.addView(topUp, l1);
        links.addView(usage);
        body.addView(links);

        // 近期用量
        body.addView(Sheets.sectionTitle(c, t, c.getString(R.string.usage_recent)));
        java.util.List<long[]> rows = db.recentUsage(7);
        if (rows.isEmpty()) {
            TextView none = Ui.text(c, c.getString(R.string.usage_none), 13, t.labelTertiary);
            none.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), Ui.dp(18));
            body.addView(none);
        } else {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.US);
            for (long[] r : rows) {
                String day;
                try {
                    day = fmt.format(new java.util.Date(
                            new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                                    .parse(String.valueOf(r[0])).getTime()));
                } catch (Exception e) {
                    day = String.valueOf(r[0]);
                }
                int hitRate = (r[4] + r[5]) == 0 ? 0 : (int) Math.round(r[4] * 100.0 / (r[4] + r[5]));
                String line = c.getString(R.string.usage_row, day, r[1],
                        compact(r[2]), compact(r[3]), hitRate);
                TextView row = Ui.text(c, line, 13, t.labelSecondary);
                row.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), Ui.dp(8));
                body.addView(row);
                View d = new View(c);
                d.setBackgroundColor(t.borderL1);
                d.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
                body.addView(d);
            }
            View bottom = new View(c);
            bottom.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(10)));
            body.addView(bottom);
        }

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body);
        sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(420)));
        card.addView(sv);

        closeRef[0] = Sheets.showCenter(root, t, card, null);

        // 异步取余额
        if (p.official()) {
            new Thread(() -> {
                String[] info = Api.balanceDetail(p);
                a.runOnUiThread(() -> {
                    if (info == null) {
                        total.setText("—");
                        detail.setText(c.getString(R.string.balance_unavailable));
                        return;
                    }
                    String cur = "CNY".equalsIgnoreCase(info[3]) ? "¥" : info[3] + " ";
                    total.setText(cur + info[0]);
                    detail.setText(c.getString(R.string.balance_granted) + " " + cur + info[1]
                            + "    " + c.getString(R.string.balance_topped) + " " + cur + info[2]);
                });
            }).start();
        } else {
            detail.setText(c.getString(R.string.balance_unavailable));
        }
    }

    private static String compact(long n) {
        if (n < 1000) return String.valueOf(n);
        return String.format(Locale.US, "%.1fk", n / 1000.0);
    }

    // ------------------------------------------------------------------ 小工具

    private static TextView label(Context c, Theme t, String text) {
        TextView tv = Ui.text(c, text, 12, t.labelTertiary);
        tv.setPadding(0, Ui.dp(10), 0, Ui.dp(4));
        return tv;
    }

    private static EditText field(Context c, Theme t, String value, String hint) {
        EditText et = new EditText(c);
        et.setText(value == null ? "" : value);
        et.setHint(hint);
        et.setHintTextColor(t.labelCaption);
        et.setTextColor(t.labelPrimary);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        et.setBackground(Ui.roundStroke(t.bgBase, t.borderL2, 1, 10));
        et.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        et.setSingleLine(true);
        return et;
    }
}
