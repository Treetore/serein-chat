package com.serein.chat.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.serein.chat.R;
import com.serein.chat.data.Models;
import com.serein.chat.data.Prefs;
import com.serein.chat.data.Provider;

import java.util.List;
import java.util.function.IntConsumer;

/** 浮层：设置面板、参考来源列表、重命名与确认对话框。 */
public final class Sheets {

    private Sheets() {
    }

    public interface SettingsHost {
        void onApiKeySaved(String key);

        void onModelChanged(String model);

        void onThemeChanged(int theme);

        void onLocaleChanged(String lang);

        void onProviderChanged();

        void onExportHistory();

        void onImportHistory();

        void onDeleteAll();

        void onClose();
    }

    // ----------------------------------------------------------- 通用浮层

    /** 在 root 上盖一层遮罩 + 底部卡片，返回关闭函数。 */
    static Runnable showBottom(FrameLayout root, Theme t, View content, Runnable onDismiss) {
        Context c = root.getContext();
        FrameLayout wrap = new FrameLayout(c);
        wrap.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(c);
        scrim.setBackgroundColor(0x66000000);
        scrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(scrim);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.BOTTOM;
        content.setLayoutParams(clp);
        wrap.addView(content);

        Runnable dismiss = () -> {
            wrap.animate().alpha(0f).setDuration(Anim.quick()).withEndAction(() -> root.removeView(wrap)).start();
            if (onDismiss != null) onDismiss.run();
        };
        scrim.setOnClickListener(v -> dismiss.run());
        root.addView(wrap);
        wrap.setAlpha(0f);
        wrap.animate().alpha(1f).setDuration(Anim.quick()).start();
        content.setTranslationY(Ui.dp(48));
        content.setScaleX(0.98f);
        content.setScaleY(0.98f);
        content.animate().translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(Anim.normal())
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                .start();
        return dismiss;
    }

    /** 居中浮层：用于余额/用量这类信息面板。 */
    static Runnable showCenter(FrameLayout root, Theme t, View content, Runnable onDismiss) {
        Context c = root.getContext();
        FrameLayout wrap = new FrameLayout(c);
        wrap.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View scrim = new View(c);
        scrim.setBackgroundColor(0x66000000);
        wrap.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                (int) (c.getResources().getDisplayMetrics().widthPixels * 0.86f),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        content.setLayoutParams(clp);
        wrap.addView(content);
        Runnable dismiss = () -> {
            wrap.animate().alpha(0f).setDuration(Anim.quick())
                    .withEndAction(() -> root.removeView(wrap)).start();
            if (onDismiss != null) onDismiss.run();
        };
        scrim.setOnClickListener(v -> dismiss.run());
        root.addView(wrap);
        wrap.setAlpha(0f);
        wrap.animate().alpha(1f).setDuration(Anim.quick()).start();
        Anim.popIn(content);
        return dismiss;
    }

    static LinearLayout sheetCard(Context c, Theme t) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(t.bgLayer2);
        float r = Ui.dp(18);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        card.setBackground(bg);
        card.setPadding(0, Ui.dp(6), 0, Ui.insetBottom);
        return card;
    }

    static void header(Context c, Theme t, LinearLayout card, String title, Runnable close) {
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(8), Ui.dp(10));
        TextView tv = Ui.text(c, title, 17, t.labelPrimary);
        tv.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView x = Ui.iconButton(c, R.drawable.ic_close, t.labelSecondary, t);
        x.setOnClickListener(v -> close.run());
        row.addView(x);
        card.addView(row);
    }

    // ----------------------------------------------------------- 设置面板

    public static void settings(Activity act, Theme t, FrameLayout root, Prefs prefs, SettingsHost host) {
        Context c = act;
        LinearLayout card = sheetCard(c, t);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        Runnable[] closeRef = new Runnable[1];
        Runnable close = () -> {
            if (closeRef[0] != null) closeRef[0].run();
        };

        Provider active = prefs.activeProvider();

        // --- 服务商 ---
        body.addView(sectionTitle(c, t, c.getString(R.string.providers)));
        body.addView(valueRow(c, t, active.name + " · "
                + (Provider.PROTO_ANTHROPIC.equals(active.protocol) ? "Anthropic" : "OpenAI")
                + " · " + trimHost(active.baseUrl), v -> {
            close.run();
            SettingsPanels.providers(act, t, root, prefs, () -> host.onProviderChanged());
        }));

        // --- 模型与能力 ---
        body.addView(sectionTitle(c, t, c.getString(R.string.model)));
        Provider.ModelInfo info = active.modelInfo(active.model);
        String modelLine = (active.model == null || active.model.isEmpty() ? "—" : active.model)
                + (info == null ? "" : "  " + capabilitySummary(c, info));
        body.addView(valueRow(c, t, modelLine, v -> {
            close.run();
            SettingsPanels.models(act, t, root, prefs, () -> host.onProviderChanged());
        }));

        // --- 地区（latest_reminder） ---
        body.addView(sectionTitle(c, t, c.getString(R.string.settings_location)));
        String loc = prefs.location();
        final View[] locRowRef = new View[1];
        locRowRef[0] = valueRow(c, t,
                loc == null || loc.isEmpty() ? c.getString(R.string.location_unset) : loc,
                v -> SettingsPanels.editLocation(act, t, prefs, () -> {
                    String now = prefs.location();
                    TextView label = valueLabel(locRowRef[0]);
                    if (label != null) {
                        label.setText(now == null || now.isEmpty()
                                ? c.getString(R.string.location_unset) : now);
                    }
                }));
        body.addView(locRowRef[0]);

        // --- 朗读音色 ---
        body.addView(sectionTitle(c, t, c.getString(R.string.tts_voice)));
        String voice = prefs.ttsVoice();
        final View[] voiceRowRef = new View[1];
        voiceRowRef[0] = valueRow(c, t,
                "tide".equals(voice) ? c.getString(R.string.tts_voice_tide)
                        : c.getString(R.string.tts_voice_stella),
                v -> {
                    prefs.setTtsVoice("tide".equals(prefs.ttsVoice()) ? "stella" : "tide");
                    TextView label = valueLabel(voiceRowRef[0]);
                    if (label != null) {
                        label.setText("tide".equals(prefs.ttsVoice())
                                ? c.getString(R.string.tts_voice_tide)
                                : c.getString(R.string.tts_voice_stella));
                    }
                });
        body.addView(voiceRowRef[0]);

        // --- 高级参数（P0-4：被服务端接管的参数说明） ---
        body.addView(sectionTitle(c, t, c.getString(R.string.advanced_params)));
        body.addView(valueRow(c, t, c.getString(R.string.advanced_params), v -> {
            new AlertDialog.Builder(c)
                    .setTitle(R.string.advanced_params)
                    .setMessage(R.string.advanced_params_body)
                    .setPositiveButton(R.string.confirm, null)
                    .show();
        }));

        // --- 外观 ---
        body.addView(sectionTitle(c, t, c.getString(R.string.appearance)));
        body.addView(valueRow(c, t,
                c.getString(R.string.theme) + " · " + themeName(c, prefs.theme())
                        + (prefs.accent() == 0 ? "" : " · " + c.getString(R.string.accent_color))
                        + (prefs.backgroundImage().isEmpty() ? "" : " · " + c.getString(R.string.bg_image)),
                v -> {
                    close.run();
                    SettingsPanels.appearance(act, t, root, prefs, () -> host.onThemeChanged(prefs.theme()));
                }));

        // --- 语言 ---
        body.addView(sectionTitle(c, t, c.getString(R.string.locale)));
        LinearLayout localeRow = Ui.row(c);
        localeRow.setPadding(Ui.dp(18), 0, Ui.dp(18), Ui.dp(6));
        String curLang = prefs.locale();
        TextView zh = themeChip(c, t, c.getString(R.string.locale_zh), "zh".equals(curLang));
        TextView en = themeChip(c, t, c.getString(R.string.locale_en), "en".equals(curLang));
        zh.setOnClickListener(v -> {
            host.onLocaleChanged("zh");
            close.run();
        });
        en.setOnClickListener(v -> {
            host.onLocaleChanged("en");
            close.run();
        });
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        zlp.rightMargin = Ui.dp(8);
        localeRow.addView(zh, zlp);
        localeRow.addView(en, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(localeRow);

        // --- 数据 ---
        body.addView(sectionTitle(c, t, c.getString(R.string.data_manage)));
        body.addView(valueRow(c, t, c.getString(R.string.export_history), v -> {
            close.run();
            host.onExportHistory();
        }));
        body.addView(valueRow(c, t, c.getString(R.string.import_history), v -> {
            close.run();
            host.onImportHistory();
        }));
        TextView del = Ui.text(c, c.getString(R.string.delete_all_sessions), 14, t.error);
        del.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(12));
        Ui.pressable(del, Ui.round(0x00000000, 10), 10, Ui.alpha(t.error, 0.12f));
        del.setOnClickListener(v -> {
            close.run();
            host.onDeleteAll();
        });
        body.addView(del);

        // --- 关于 ---
        body.addView(sectionTitle(c, t, c.getString(R.string.about)));
        body.addView(iconRow(c, t, R.drawable.ic_github,
                c.getString(R.string.settings_github),
                c.getString(R.string.github_subtitle),
                v -> openUrl(c, "https://github.com/Treetore/serein-chat")));
        TextView about = Ui.text(c, c.getString(R.string.about_body,
                trimHost(active.baseUrl) + endpointHint(active)), 13, t.labelTertiary);
        about.setLineSpacing(Ui.dp(4), 1f);
        about.setPadding(Ui.dp(18), 0, Ui.dp(18), Ui.dp(18));
        body.addView(about);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(Ui.dp(520),
                (int) (c.getResources().getDisplayMetrics().heightPixels * 0.72f))));

        header(c, t, card, c.getString(R.string.settings), close);
        card.addView(sv);
        closeRef[0] = showBottom(root, t, card, host::onClose);
    }

    /** 带图标的可点行（GitHub 等项目链接）。 */
    static View iconRow(Context c, Theme t, int icon, String title, String subtitle,
                        View.OnClickListener l) {
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(10));
        Ui.pressable(row, Ui.round(0x00000000, 10), 10, t.hoverAccent);
        row.addView(Ui.icon(c, icon, t.labelSecondary, 20));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Ui.dp(12);
        col.setLayoutParams(clp);
        col.addView(Ui.text(c, title, 14, t.labelPrimary));
        col.addView(Ui.text(c, subtitle, 11, t.labelTertiary));
        row.addView(col);
        row.addView(Ui.icon(c, R.drawable.ic_expand_more, t.labelCaption, 16));
        row.setOnClickListener(l);
        return row;
    }

    /** 取可点行里的值文字（用于原地刷新，避免整屏重建）。 */
    static TextView valueLabel(View row) {
        if (row instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) row;
            for (int i = 0; i < ll.getChildCount(); i++) {
                if (ll.getChildAt(i) instanceof TextView) return (TextView) ll.getChildAt(i);
            }
        }
        return null;
    }

    private static View valueRow(Context c, Theme t, String value, View.OnClickListener l) {
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.dp(18), Ui.dp(11), Ui.dp(18), Ui.dp(11));
        Ui.pressable(row, Ui.round(0x00000000, 10), 10, t.hoverAccent);
        TextView tv = Ui.text(c, value, 14, t.labelSecondary);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.icon(c, R.drawable.ic_expand_more, t.labelCaption, 16));
        row.setOnClickListener(l);
        return row;
    }

    private static String trimHost(String url) {
        if (url == null) return "";
        return url.replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    private static String endpointHint(Provider p) {
        return Provider.PROTO_ANTHROPIC.equals(p.protocol) ? "/v1/messages" : "/v1/chat/completions";
    }

    private static String themeName(Context c, int pref) {
        if (pref == Prefs.THEME_LIGHT) return c.getString(R.string.theme_light);
        if (pref == Prefs.THEME_DARK) return c.getString(R.string.theme_dark);
        return c.getString(R.string.theme_system);
    }

    private static String capabilitySummary(Context c, Provider.ModelInfo m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.vision ? "👁" : "—").append(' ').append(m.tools ? "🔧" : "—");
        if (m.thinking) sb.append(" 💭");
        if ("manual".equals(m.source)) sb.append(" ✎");
        return sb.toString();
    }
    static TextView sectionTitle(Context c, Theme t, String s) {
        TextView tv = Ui.text(c, s, 12, t.labelTertiary);
        tv.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(8));
        return tv;
    }

    private static View radioRow(Context c, Theme t, String label, boolean checked, View.OnClickListener l) {
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.dp(18), Ui.dp(11), Ui.dp(18), Ui.dp(11));
        Ui.pressable(row, Ui.round(0x00000000, 10), 10, t.hoverAccent);
        TextView tv = Ui.text(c, label, 14, t.labelPrimary);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (checked) row.addView(Ui.icon(c, R.drawable.ic_check, t.brandText, 18));
        row.setOnClickListener(l);
        return row;
    }

    static TextView themeChip(Context c, Theme t, String label, boolean selected) {
        TextView tv = Ui.text(c, label, 14, selected ? t.brandText : t.labelPrimary);
        tv.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        tv.setBackground(selected
                ? Ui.roundStroke(t.ghostActiveFill, t.ghostActiveBorder, 1, 16)
                : Ui.roundStroke(0x00000000, t.borderL2, 1, 16));
        return tv;
    }

    /** 两个胶囊互斥选中态。 */
    static void chipSelect(TextView a, TextView b, boolean aSelected, Theme t) {
        a.setTextColor(aSelected ? t.brandText : t.labelPrimary);
        a.setBackground(aSelected
                ? Ui.roundStroke(t.ghostActiveFill, t.ghostActiveBorder, 1, 16)
                : Ui.roundStroke(0x00000000, t.borderL2, 1, 16));
        b.setTextColor(!aSelected ? t.brandText : t.labelPrimary);
        b.setBackground(!aSelected
                ? Ui.roundStroke(t.ghostActiveFill, t.ghostActiveBorder, 1, 16)
                : Ui.roundStroke(0x00000000, t.borderL2, 1, 16));
    }

    // ----------------------------------------------------------- 参考来源

    public static void sources(Context c, Theme t, FrameLayout root, List<Models.Source> sources) {
        LinearLayout card = sheetCard(c, t);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        if (sources == null || sources.isEmpty()) {
            TextView empty = Ui.text(c, c.getString(R.string.message_search_no_result), 14, t.labelTertiary);
            empty.setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(24));
            body.addView(empty);
        } else {
            for (int i = 0; i < sources.size(); i++) {
                Models.Source s = sources.get(i);
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(10));
                Ui.pressable(row, Ui.round(0x00000000, 10), 10, t.hoverAccent);

                TextView idx = Ui.text(c, (i + 1) + ". " + (s.site.isEmpty() ? "" : s.site + " · "), 12, t.labelTertiary);
                row.addView(idx);
                TextView title = Ui.text(c, s.title == null || s.title.isEmpty() ? s.url : s.title,
                        14, t.labelPrimary);
                title.setLineSpacing(Ui.dp(3), 1f);
                row.addView(title);
                if (s.snippet != null && !s.snippet.isEmpty()) {
                    TextView sn = Ui.text(c, s.snippet, 13, t.labelTertiary);
                    sn.setMaxLines(3);
                    sn.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    sn.setLineSpacing(Ui.dp(3), 1f);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = Ui.dp(3);
                    row.addView(sn, lp);
                }
                row.setOnClickListener(v -> openUrl(c, s.url));
                body.addView(row);
                View d = new View(c);
                d.setBackgroundColor(t.borderL1);
                d.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
                body.addView(d);
            }
        }
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body);
        sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(Ui.dp(480), (int) (c.getResources().getDisplayMetrics().heightPixels * 0.6f))));

        Runnable[] closeRef = new Runnable[1];
        header(c, t, card, c.getString(R.string.source_list_title), () -> {
            if (closeRef[0] != null) closeRef[0].run();
        });
        card.addView(sv);
        closeRef[0] = showBottom(root, t, card, null);
    }

    private static void openUrl(Context c, String url) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            Ui.toast(c, c.getString(R.string.open_link_failed));
        }
    }

    // ----------------------------------------------------------- 输入类对话框

    public static void rename(Activity act, Theme t, String current, java.util.function.Consumer<String> done) {
        EditText et = new EditText(act);
        et.setText(current == null ? "" : current);
        et.setHint(R.string.untitled_chat);
        et.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), Ui.dp(12));
        new AlertDialog.Builder(act)
                .setTitle(R.string.rename)
                .setView(et)
                .setPositiveButton(R.string.confirm, (d, w) -> done.accept(et.getText().toString().trim()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void confirm(Activity act, String title, String desc, Runnable ok) {
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(desc)
                .setPositiveButton(R.string.confirm, (d, w) -> ok.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void choice(Activity act, String title, String[] items, int checked, IntConsumer picked) {
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    picked.accept(which);
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
