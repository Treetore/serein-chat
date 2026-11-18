package com.serein.chat.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.serein.chat.R;
import com.serein.chat.data.Models;
import com.serein.chat.md.MarkdownView;

import java.util.ArrayList;
import java.util.List;

/** 单条消息的行视图：用户气泡 / 助手回复（思考块、检索结果、Markdown 正文、操作栏）。 */
public final class MessageRow extends LinearLayout {

    public interface Host {
        void onCopy(Models.Message m);

        void onRegenerate(Models.Message m);

        void onSpeak(Models.Message m);

        void onOpenSources(Models.Message m);

        void onRetry(Models.Message m);

        void onSwitchVersion(Models.Message m, int delta);

        void onEditMessage(Models.Message m);

        void onContinue(Models.Message m);

        void onShare(Models.Message m);
    }

    private final Theme t;
    private final Host host;
    private final int maxBubbleWidth;
    private Models.Message msg;

    private String structureKey = "";
    private MarkdownView mdText;
    private TextView thinkBody;
    private TextView thinkLabel;
    private ImageView thinkChevron;
    private LinearLayout thinkWrap;
    private boolean thinkExpanded;
    private TextView statusPill;
    private LinearLayout actionsRow;
    private TextView errorText;
    private final List<TextView> pillLabels = new ArrayList<>();

    public MessageRow(Context c, Theme t, Models.Message m, Host host) {
        super(c);
        this.t = t;
        this.host = host;
        this.msg = m;
        this.maxBubbleWidth = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.80f);
        setOrientation(VERTICAL);
        setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        build();
    }

    public void setMessage(Models.Message m) {
        this.msg = m;
        apply();
    }

    /** 该行当前绑定的消息，供列表寻找视图。 */
    public Models.Message tag() {
        return msg;
    }

    private void apply() {
        String key = key(msg);
        if (!key.equals(structureKey)) {
            build();
        } else {
            refresh();
        }
    }

    private String key(Models.Message m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.role).append('|').append(m.status).append('|').append(m.alternatives.size())
                .append('|').append(m.activeVersion);
        for (Models.Block b : m.blocks) sb.append(b.type).append(',');
        if (!m.files.isEmpty()) sb.append("f").append(m.files.size());
        return sb.toString();
    }

    // ------------------------------------------------------------------ 构建

    private void build() {
        structureKey = key(msg);
        removeAllViews();
        mdText = null;
        thinkBody = null;
        thinkWrap = null;
        actionsRow = null;
        errorText = null;
        pillLabels.clear();
        if (msg.role == Models.ROLE_USER) buildUser();
        else buildAssistant();
    }

    private void buildUser() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.END);
        col.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        if (!msg.files.isEmpty()) {
            LinearLayout files = new LinearLayout(getContext());
            files.setOrientation(HORIZONTAL);
            files.setGravity(Gravity.END);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            flp.bottomMargin = Ui.dp(8);
            files.setLayoutParams(flp);
            for (int i = msg.files.size() - 1; i >= 0; i--) {
                files.addView(fileChip(msg.files.get(i)));
            }
            col.addView(files);
        }

        TextView bubble = new TextView(getContext());
        bubble.setText(msg.text);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        bubble.setTextColor(t.labelPrimary);
        bubble.setLineSpacing(Ui.dp(4), 1f);
        bubble.setIncludeFontPadding(false);
        bubble.setTextIsSelectable(true);
        bubble.setBackground(Ui.round(t.bubble, 22));
        bubble.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(10));
        bubble.setMaxWidth(maxBubbleWidth);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.END;
        bubble.setLayoutParams(blp);
        bubble.setOnLongClickListener(v -> {
            showUserMenu(v);
            return true;
        });
        bubble.setOnClickListener(this::showUserMenu);
        col.addView(bubble);
        addView(col);
    }

    private View fileChip(Models.FileRef f) {
        LinearLayout box;
        if (f.image && f.localPath != null && !f.localPath.isEmpty()) {
            box = new LinearLayout(getContext());
            box.setOrientation(VERTICAL);
            ImageView iv = new ImageView(getContext());
            try {
                android.graphics.Bitmap bm = decodeSampled(f.localPath, Ui.dp(160));
                if (bm != null) {
                    iv.setImageBitmap(bm);
                } else {
                    iv.setImageResource(R.drawable.ic_file);
                    iv.setColorFilter(t.labelCaption);
                }
            } catch (Exception e) {
                iv.setImageResource(R.drawable.ic_file);
                iv.setColorFilter(t.labelCaption);
            }
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int s = Ui.dp(64);
            iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
            iv.setBackground(Ui.round(t.codeBlock, 12));
            iv.setClipToOutline(true);
            box.addView(iv);
        } else {
            box = Ui.row(getContext());
            box.setBackground(Ui.roundStroke(t.bgLayer2, t.borderL2, 1, 10));
            box.setPadding(Ui.dp(10), Ui.dp(7), Ui.dp(12), Ui.dp(7));
            box.addView(Ui.icon(getContext(), R.drawable.ic_file, t.labelTertiary, 16));
            TextView name = Ui.text(getContext(), f.name, 13, t.labelSecondary);
            name.setMaxWidth(Ui.dp(150));
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            nlp.leftMargin = Ui.dp(6);
            box.addView(name, nlp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(8);
        box.setLayoutParams(lp);
        return box;
    }

    private void buildAssistant() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // 头像与桌面图标一致
        ImageView logo = new ImageView(getContext());
        logo.setImageResource(R.mipmap.ic_launcher_round);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int s = Ui.dp(26);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(s, s);
        llp.topMargin = Ui.dp(2);
        llp.rightMargin = Ui.dp(10);
        logo.setLayoutParams(llp);
        row.addView(logo);

        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        // 思考块（流式开始时正文为空，先占位显示“正在思考”）
        for (Models.Block b : msg.blocks) {
            if (b.type == Models.B_THINKING) {
                col.addView(thinkingSection(b));
                break;
            }
        }

        // 检索状态条
        LinearLayout pills = new LinearLayout(getContext());
        pills.setOrientation(VERTICAL);
        pills.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        boolean hasSearchResult = false;
        boolean hasFetchResult = false;
        for (Models.Block b : msg.blocks) {
            if (b.type == Models.B_SEARCH_RESULT) hasSearchResult = true;
            if (b.type == Models.B_FETCH_RESULT) hasFetchResult = true;
        }
        for (Models.Block b : msg.blocks) {
            if (b.type == Models.B_SEARCH_RESULT || b.type == Models.B_FETCH_RESULT) {
                View pill = pillRow(b);
                if (pill != null) pills.addView(pill);
            } else if (b.type == Models.B_TOOL_USE) {
                // 结果块到达后不再显示“搜索中”占位
                boolean searching = b.kind != null && b.kind.contains("search");
                if (searching ? hasSearchResult : hasFetchResult) continue;
                View pill = pillRow(b);
                if (pill != null) pills.addView(pill);
            }
        }
        if (pills.getChildCount() > 0) col.addView(pills);

        // 正文
        mdText = new MarkdownView(getContext(), t);
        mdText.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        mdText.render(collectText());
        col.addView(mdText);

        // 引用来源
        int citationCount = 0;
        for (Models.Block b : msg.blocks) citationCount += b.citations.size();
        if (citationCount > 0) {
            TextView cite = Ui.text(getContext(),
                    getContext().getString(R.string.citations_count, citationCount), 12, t.brandText);
            cite.setPadding(Ui.dp(8), Ui.dp(3), Ui.dp(8), Ui.dp(3));
            Ui.pressable(cite, t.ghostActiveFill, 8, t.hoverAccent);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            clp.topMargin = Ui.dp(8);
            cite.setOnClickListener(v -> host.onOpenSources(msg));
            col.addView(cite, clp);
        }

        // 等待指示
        if (msg.blocks.isEmpty() || !msg.hasVisibleText() && !hasThinking()) {
            if (Models.S_PENDING.equals(msg.status) || Models.S_STREAMING.equals(msg.status)) {
                col.addView(waitingRow());
            }
        }

        // 错误
        if (msg.error != null && !msg.error.isEmpty()) {
            errorText = Ui.text(getContext(), msg.error, 14, t.error);
            errorText.setPadding(0, Ui.dp(8), 0, 0);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            col.addView(errorText, elp);
        }

        // 操作栏
        col.addView(actionBar());

        // 用量信息（token 速度 / 缓存命中 / 输入输出）
        if (msg.usage != null && msg.usage.outputTokens > 0) {
            col.addView(usageLine(msg.usage));
        }

        row.addView(col);
        addView(row);
    }

    private boolean hasThinking() {
        for (Models.Block b : msg.blocks) {
            if (b.type == Models.B_THINKING && !b.text.trim().isEmpty()) return true;
        }
        return false;
    }

    private View thinkingSection(Models.Block block) {
        thinkWrap = new LinearLayout(getContext());
        thinkWrap.setOrientation(VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        wlp.bottomMargin = Ui.dp(8);
        thinkWrap.setLayoutParams(wlp);

        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
        Ui.pressable(header, Ui.round(t.codeBlock, 10), 10, t.hoverAccent);
        header.addView(Ui.icon(getContext(), R.drawable.ic_bulb, t.labelTertiary, 15));
        thinkLabel = Ui.text(getContext(), "", 13, t.labelSecondary);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(7);
        header.addView(thinkLabel, tlp);
        thinkChevron = Ui.icon(getContext(), R.drawable.ic_expand_more, t.labelCaption, 16);
        header.addView(thinkChevron);
        thinkWrap.addView(header);

        thinkBody = new TextView(getContext());
        thinkBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        thinkBody.setTextColor(t.labelTertiary);
        thinkBody.setLineSpacing(Ui.dp(4), 1f);
        thinkBody.setIncludeFontPadding(false);
        thinkBody.setTextIsSelectable(true);
        thinkBody.setPadding(Ui.dp(13), Ui.dp(8), Ui.dp(8), Ui.dp(8));
        thinkBody.setText(block.text);
        thinkBody.setVisibility(GONE);
        thinkWrap.addView(thinkBody);

        header.setOnClickListener(v -> {
            thinkExpanded = !thinkExpanded;
            thinkBody.setVisibility(thinkExpanded ? VISIBLE : GONE);
            thinkChevron.setRotation(thinkExpanded ? 180f : 0f);
        });
        updateThinkHeader(block);
        return thinkWrap;
    }

    private void updateThinkHeader(Models.Block block) {
        if (thinkLabel == null) return;
        boolean streaming = Models.S_PENDING.equals(msg.status) || Models.S_STREAMING.equals(msg.status);
        boolean finishedBlock = block.endedAt > 0 || !streaming;
        if (!finishedBlock) {
            thinkLabel.setText(getContext().getString(R.string.message_thinking));
        } else {
            long ms = block.endedAt > 0 ? block.endedAt - block.startedAt : 0;
            long sec = Math.max(1, Math.round(ms / 1000.0));
            String txt = getContext().getString(R.string.message_thinked, (int) sec);
            thinkLabel.setText(txt);
        }
    }

    private View waitingRow() {
        LinearLayout row = Ui.row(getContext());
        row.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, Ui.dp(28)));
        for (int i = 0; i < 3; i++) {
            View dot = new View(getContext());
            dot.setBackground(Ui.circle(t.labelDimmed));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(6), Ui.dp(6));
            lp.rightMargin = Ui.dp(4);
            row.addView(dot, lp);
            dot.setAlpha(0.35f);
            dot.animate().alpha(1f).setDuration(500).setStartDelay(i * 120L)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            dot.animate().alpha(0.35f).setDuration(500).withEndAction(this).start();
                        }
                    }).start();
        }
        return row;
    }

    /** 搜索 / 阅读状态条。 */
    private View pillRow(Models.Block b) {
        LinearLayout pill = Ui.row(getContext());
        pill.setBackground(Ui.roundStroke(t.bgBase, t.borderL2, 1, 100));
        pill.setPadding(Ui.dp(10), Ui.dp(5), Ui.dp(12), Ui.dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(8);
        pill.setLayoutParams(lp);

        int iconRes = b.type == Models.B_FETCH_RESULT ? R.drawable.ic_link : R.drawable.ic_globe;
        pill.addView(Ui.icon(getContext(), iconRes, t.labelTertiary, 14));

        TextView label = Ui.text(getContext(), pillText(b), 13, t.labelSecondary);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.dp(6);
        pill.addView(label, tlp);
        pillLabels.add(label);

        if (b.type == Models.B_SEARCH_RESULT && !b.sources.isEmpty()) {
            Ui.pressable(pill, Ui.roundStroke(t.bgBase, t.borderL2, 1, 100), 100, t.hoverAccent);
            pill.setOnClickListener(v -> host.onOpenSources(msg));
        } else if (b.type == Models.B_TOOL_USE) {
            // 工具调用占位，无需交互
        }
        return pill;
    }

    private String pillText(Models.Block b) {
        Context c = getContext();
        boolean live = Models.S_PENDING.equals(msg.status) || Models.S_STREAMING.equals(msg.status);
        if (b.type == Models.B_TOOL_USE) {
            if (b.kind != null && b.kind.contains("deep_search")) {
                return c.getString(R.string.message_deep_searching);
            }
            if (b.kind != null && b.kind.contains("search")) {
                return c.getString(R.string.message_searching);
            }
            return c.getString(R.string.message_reading);
        }
        if (b.type == Models.B_SEARCH_RESULT) {
            if (b.failed) return c.getString(R.string.tool_search_fail);
            int n = b.sources.size();
            if (n == 0) return c.getString(R.string.message_search_no_result);
            if (b.kind != null && b.kind.contains("deep_search")) {
                return c.getString(R.string.message_deep_search_results, n);
            }
            return c.getString(R.string.message_search_results, n);
        }
        if (b.type == Models.B_FETCH_RESULT) {
            if (b.failed) return c.getString(R.string.message_search_no_result);
            if (live && b.fetchTitle.isEmpty()) return c.getString(R.string.message_reading);
            return b.fetchTitle.isEmpty() ? c.getString(R.string.message_search_result) : b.fetchTitle;
        }
        return "";
    }

    private View actionBar() {
        actionsRow = Ui.row(getContext());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(6);
        actionsRow.setLayoutParams(alp);

        boolean streaming = Models.S_PENDING.equals(msg.status) || Models.S_STREAMING.equals(msg.status);
        if (streaming) {
            actionsRow.setVisibility(GONE);
            return actionsRow;
        }
        actionsRow.setVisibility(VISIBLE);

        // 版本切换
        if (msg.alternatives.size() > 0) {
            int total = msg.alternatives.size();
            LinearLayout sw = Ui.row(getContext());
            TextView cur = Ui.text(getContext(), (msg.activeVersion + 1) + "/" + total, 13, t.labelSecondary);
            ImageView prev = Ui.icon(getContext(), R.drawable.ic_expand_more, t.labelTertiary, 16);
            prev.setRotation(90f);
            ImageView next = Ui.icon(getContext(), R.drawable.ic_expand_more, t.labelTertiary, 16);
            next.setRotation(-90f);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22));
            prev.setLayoutParams(ilp);
            next.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
            prev.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
            next.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
            prev.setOnClickListener(v -> host.onSwitchVersion(msg, -1));
            next.setOnClickListener(v -> host.onSwitchVersion(msg, 1));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            clp.leftMargin = Ui.dp(4);
            clp.rightMargin = Ui.dp(4);
            sw.addView(prev);
            sw.addView(cur, clp);
            sw.addView(next);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            slp.rightMargin = Ui.dp(14);
            actionsRow.addView(sw, slp);
        }

        actionsRow.addView(actionButton(R.drawable.ic_copy, R.string.copy, v -> host.onCopy(msg)),
                actionLp());
        actionsRow.addView(actionButton(R.drawable.ic_refresh, R.string.regenerate, v -> host.onRegenerate(msg)),
                actionLp());
        actionsRow.addView(actionButton(R.drawable.ic_speaker, R.string.read_aloud, v -> host.onSpeak(msg)),
                actionLp());
        actionsRow.addView(actionButton(R.drawable.ic_share, R.string.share, v -> host.onShare(msg)),
                actionLp());
        if (msg.error != null && !msg.error.isEmpty()) {
            actionsRow.addView(actionButton(R.drawable.ic_refresh, R.string.retry, v -> host.onRetry(msg)),
                    actionLp());
        }
        if (canContinue()) {
            actionsRow.addView(actionButton(R.drawable.ic_arrow_downward, R.string.continue_generating,
                    v -> host.onContinue(msg)), actionLp());
        }
        return actionsRow;
    }

    private void showUserMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(getContext(), anchor);
        menu.getMenu().add(0, 1, 0, getContext().getString(R.string.copy));
        menu.getMenu().add(0, 2, 1, getContext().getString(R.string.edit_message));
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) host.onCopy(msg);
            else if (item.getItemId() == 2) host.onEditMessage(msg);
            return true;
        });
        menu.show();
    }

    /** 仅正常结束或被停止、且有正文的回复可以续写。 */
    private boolean canContinue() {
        if (!Models.S_STOPPED.equals(msg.status) && !Models.S_DONE.equals(msg.status)) return false;
        if ("max_tokens".equals(msg.stopReason)) return true;
        return Models.S_STOPPED.equals(msg.status) && msg.hasVisibleText()
                && !"refusal".equals(msg.stopReason);
    }

    /** 底部用量条：↑输入 ↓输出 · 速度 · 缓存命中率。 */
    private View usageLine(com.serein.chat.data.Usage u) {
        Context ctx = getContext();
        StringBuilder sb = new StringBuilder();
        sb.append("↑").append(compact(u.inputTokens))
          .append(" ↓").append(compact(u.outputTokens))
          .append(" · ").append(String.format(java.util.Locale.US, "%.1f", u.tokensPerSecond()))
          .append(" tok/s · ").append(ctx.getString(R.string.usage_cache, Math.round(u.cacheRate())));
        if (u.cacheHit > 0) {
            sb.append("（").append(ctx.getString(R.string.usage_hit, compact(u.cacheHit))).append("）");
        }
        if (u.firstTokenMs > 0) {
            sb.append(" · ").append(ctx.getString(R.string.usage_ft,
                    String.format(java.util.Locale.US, "%.1fs", u.firstTokenMs / 1000.0)));
        }
        String line = sb.toString();
        TextView tv = Ui.text(getContext(), line, 11, t.labelCaption);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(2);
        lp.leftMargin = Ui.dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private String compact(int n) {
        if (n < 1000) return String.valueOf(n);
        return String.format(java.util.Locale.US, "%.1fk", n / 1000.0);
    }

    private LinearLayout.LayoutParams actionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(6);
        return lp;
    }

    private View actionButton(int icon, int labelRes, View.OnClickListener l) {
        LinearLayout box = Ui.row(getContext());
        box.setPadding(Ui.dp(6), Ui.dp(4), Ui.dp(8), Ui.dp(4));
        Ui.pressable(box, 0x00000000, 8, t.hoverAccent);
        box.addView(Ui.icon(getContext(), icon, t.labelTertiary, 15));
        TextView tv = Ui.text(getContext(), getContext().getString(labelRes), 12, t.labelTertiary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(5);
        box.addView(tv, lp);
        box.setOnClickListener(l);
        return box;
    }

    // ------------------------------------------------------------------ 刷新

    private void refresh() {
        if (msg.role == Models.ROLE_ASSISTANT) {
            for (Models.Block b : msg.blocks) {
                if (b.type == Models.B_THINKING && thinkBody != null && !b.text.trim().isEmpty()) {
                    if (!thinkBody.getText().toString().equals(b.text)) thinkBody.setText(b.text);
                    updateThinkHeader(b);
                    break;
                }
            }
            if (mdText != null) mdText.render(collectText());
            // 状态条文案（与构建时的过滤顺序保持一致）
            boolean hasSearchResult = false;
            boolean hasFetchResult = false;
            for (Models.Block b : msg.blocks) {
                if (b.type == Models.B_SEARCH_RESULT) hasSearchResult = true;
                if (b.type == Models.B_FETCH_RESULT) hasFetchResult = true;
            }
            int pi = 0;
            for (Models.Block b : msg.blocks) {
                boolean isPill = b.type == Models.B_SEARCH_RESULT || b.type == Models.B_FETCH_RESULT;
                if (b.type == Models.B_TOOL_USE) {
                    boolean searching = b.kind != null && b.kind.contains("search");
                    isPill = !(searching ? hasSearchResult : hasFetchResult);
                }
                if (isPill) {
                    if (pi < pillLabels.size()) pillLabels.get(pi).setText(pillText(b));
                    pi++;
                }
            }
        }
        // 状态变化时重建（操作栏 / 等待点）
        String key = key(msg);
        if (!key.equals(structureKey)) build();
    }

    /** 汇总所有文本块，供 Markdown 渲染。 */
    private String collectText() {
        StringBuilder sb = new StringBuilder();
        for (Models.Block b : msg.blocks) {
            if (b.type == Models.B_TEXT) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(b.text);
            }
        }
        return sb.toString();
    }

    /** 按目标尺寸二次采样解码，避免大图在列表里 OOM。 */
    private static android.graphics.Bitmap decodeSampled(String path, int targetPx) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            while (max / sample > targetPx * 2) sample *= 2;
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    public static CharSequence timeAgo(Context c, long ts) {
        return DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS);
    }
}
