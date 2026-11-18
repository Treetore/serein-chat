package com.serein.chat.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.serein.chat.R;
import com.serein.chat.data.Models;

import java.util.List;

/** 底部输入卡片：多行输入 + 附件 + 深度思考 / 智能搜索开关 + 语音 + 发送/停止。 */
public final class InputBar extends LinearLayout {

    public interface Host {
        void onSend(String text);

        void onStopGenerating();

        void onAttach();

        void onToggleThinking(boolean on);

        void onToggleSearch(boolean on);

        void onVoice();

        void onDraftChanged(String text);

        void onThinkingLevelChanged(int level);
    }

    private final Theme t;
    private ImageView voiceButton;
    private final Host host;
    private final EditText input;
    private final LinearLayout attachments;
    private final LinearLayout thinkChip;
    private final LinearLayout searchChip;
    private final ImageView sendButton;
    private final TextView thinkLabel;
    private final TextView searchLabel;
    private TextView levelBadge;
    private boolean strengthVisible;
    private int currentLevel = 1;
    private boolean streaming;
    private boolean thinking;
    private boolean search;

    public InputBar(Context c, Theme t, Host host) {
        super(c);
        this.t = t;
        this.host = host;
        setOrientation(VERTICAL);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(VERTICAL);
        card.setBackground(Ui.roundStroke(t.inputMajor, t.borderL2, 1, 24));
        card.setClipToOutline(true);
        card.setElevation(Ui.dp(3));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardLp.leftMargin = Ui.dp(12);
        cardLp.rightMargin = Ui.dp(12);
        cardLp.bottomMargin = Ui.dp(8);
        cardLp.topMargin = Ui.dp(4);
        card.setLayoutParams(cardLp);

        attachments = new LinearLayout(c);
        attachments.setOrientation(HORIZONTAL);
        attachments.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), 0);
        attachments.setVisibility(GONE);
        card.addView(attachments);

        input = new EditText(c);
        input.setBackground(null);
        input.setHint(R.string.input_placeholder);
        input.setHintTextColor(t.labelCaption);
        input.setTextColor(t.labelPrimary);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setLineSpacing(Ui.dp(4), 1f);
        input.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(4));
        input.setMinLines(1);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_NONE);
        input.setVerticalScrollBarEnabled(false);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                updateSendState();
            }

            @Override
            public void afterTextChanged(Editable s) {
                host.onDraftChanged(s == null ? "" : s.toString());
            }
        });
        card.addView(input, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout functionRow = Ui.row(c);
        functionRow.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(8));
        card.addView(functionRow, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // 左侧：附件 + 开关芯片，窄屏时横向滚动，避免把发送键挤出屏幕
        LinearLayout leftGroup = Ui.row(c);
        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(c);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setFillViewport(true);
        chipScroll.addView(leftGroup, new android.widget.HorizontalScrollView.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        functionRow.addView(chipScroll, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        ImageView attach = Ui.iconButton(c, R.drawable.ic_attach, t.labelSecondary, t);
        attach.setContentDescription(c.getString(R.string.upload_file));
        attach.setOnClickListener(v -> host.onAttach());
        leftGroup.addView(attach);

        thinkChip = chip(c, R.drawable.ic_bulb, c.getString(R.string.thinking_switch));
        thinkLabel = (TextView) thinkChip.getChildAt(1);
        thinkChip.setOnClickListener(v -> {
            thinking = !thinking;
            styleChip(thinkChip, thinking);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            host.onToggleThinking(thinking);
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp1.leftMargin = Ui.dp(6);
        leftGroup.addView(thinkChip, lp1);

        searchChip = chip(c, R.drawable.ic_globe, c.getString(R.string.search_switch));
        searchLabel = (TextView) searchChip.getChildAt(1);
        searchChip.setOnClickListener(v -> {
            search = !search;
            styleChip(searchChip, search);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            host.onToggleSearch(search);
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp2.leftMargin = Ui.dp(6);
        leftGroup.addView(searchChip, lp2);

        // 右侧固定：当前思考强度徽标 + 语音 + 发送
        levelBadge = Ui.text(c, "", 12, t.brandText);
        levelBadge.setPadding(Ui.dp(10), Ui.dp(5), Ui.dp(10), Ui.dp(5));
        levelBadge.setBackground(Ui.roundStroke(t.ghostActiveFill, t.ghostActiveBorder, 1, 100));
        levelBadge.setVisibility(GONE);
        levelBadge.setSingleLine(true);
        levelBadge.setGravity(Gravity.CENTER);
        // 徽标即入口：点开出三档子选单
        levelBadge.setOnClickListener(this::showStrengthMenu);
        Ui.pressable(levelBadge, Ui.roundStroke(t.ghostActiveFill, t.ghostActiveBorder, 1, 100), 100, t.hoverAccent);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lbLp.rightMargin = Ui.dp(4);
        functionRow.addView(levelBadge, lbLp);

        voiceButton = Ui.iconButton(c, R.drawable.ic_mic, t.labelSecondary, t);
        voiceButton.setContentDescription(c.getString(R.string.voice_input));
        voiceButton.setOnClickListener(v -> host.onVoice());
        functionRow.addView(voiceButton);

        sendButton = new ImageView(c);
        int size = Ui.dp(34);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(size, size);
        slp.leftMargin = Ui.dp(6);
        sendButton.setLayoutParams(slp);
        sendButton.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
        sendButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sendButton.setImageResource(R.drawable.ic_arrow_up);
        sendButton.setColorFilter(0xFFFFFFFF);
        sendButton.setContentDescription(c.getString(R.string.send));
        Ui.pressable(sendButton, Ui.circle(t.brand), 17, 0x40FFFFFF);
        sendButton.setOnClickListener(v -> {
            if (streaming) {
                host.onStopGenerating();
            } else {
                String text = input.getText().toString().trim();
                if (!text.isEmpty()) host.onSend(text);
            }
        });
        functionRow.addView(sendButton);

        addView(card);
        updateSendState();
    }

    private void showStrengthMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(getContext(), anchor);
        menu.getMenu().add(0, 0, 0, getContext().getString(R.string.level_low));
        menu.getMenu().add(0, 1, 1, getContext().getString(R.string.level_mid));
        menu.getMenu().add(0, 2, 2, getContext().getString(R.string.level_high));
        menu.setOnMenuItemClickListener(item -> {
            setLevel(item.getItemId());
            host.onThinkingLevelChanged(item.getItemId());
            return true;
        });
        menu.show();
    }

    /** 只在 DeepSeek 官方服务下显示思考强度入口。 */
    public void setStrengthVisible(boolean visible) {
        strengthVisible = visible;
        if (levelBadge != null) levelBadge.setVisibility(visible ? VISIBLE : GONE);
    }

    public void setLevel(int level) {
        currentLevel = level;
        if (levelBadge == null) return;
        String name = getContext().getString(level == 0 ? R.string.level_low
                : level == 2 ? R.string.level_high : R.string.level_mid);
        levelBadge.setText(getContext().getString(R.string.strength_badge, name));
        levelBadge.setVisibility(strengthVisible ? VISIBLE : GONE);
    }

    private LinearLayout chip(Context c, int icon, String label) {
        LinearLayout box = Ui.row(c);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, Ui.dp(34)));
        ImageView iv = Ui.icon(c, icon, t.labelSecondary, 15);
        box.addView(iv);
        TextView tv = Ui.text(c, label, 13, t.labelPrimary);
        tv.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(5);
        box.addView(tv, lp);
        styleChip(box, false);
        Anim.attachSpringPress(box);
        return box;
    }

    private void styleChip(LinearLayout box, boolean selected) {
        ImageView iv = (ImageView) box.getChildAt(0);
        TextView tv = (TextView) box.getChildAt(1);
        if (selected) {
            box.setBackground(Ui.roundStroke(t.ghostActiveFill, t.ghostActiveBorder, 1, 18));
            iv.setColorFilter(t.brandText);
            tv.setTextColor(t.brandText);
        } else {
            box.setBackground(Ui.roundStroke(0x00000000, t.borderL2, 1, 18));
            iv.setColorFilter(t.labelSecondary);
            tv.setTextColor(t.labelPrimary);
        }
    }

    // ------------------------------------------------------------------ 状态

    public void setToggles(boolean thinkingOn, boolean searchOn) {
        thinking = thinkingOn;
        search = searchOn;
        styleChip(thinkChip, thinkingOn);
        styleChip(searchChip, searchOn);
    }

    public void setStreaming(boolean on) {
        streaming = on;
        sendButton.setImageResource(on ? R.drawable.ic_stop : R.drawable.ic_arrow_up);
        sendButton.setContentDescription(getContext().getString(
                on ? R.string.stop_generating : R.string.send));
        Ui.pressable(sendButton, Ui.circle(on ? t.labelPrimary : t.brand), 17, 0x40FFFFFF);
        sendButton.setColorFilter(on ? t.bgBase : 0xFFFFFFFF);
        updateSendState();
    }

    private void updateSendState() {
        boolean has = !input.getText().toString().trim().isEmpty();
        sendButton.setAlpha(streaming || has ? 1f : 0.4f);
    }

    public void setEnabledInput(boolean enabled) {
        input.setEnabled(enabled);
    }

    /** 外部写入文本（语音识别结果等），光标置尾。 */
    public void setText(String value) {
        input.setText(value == null ? "" : value);
        input.setSelection(input.getText().length());
    }

    /** 录音态：麦克风按钮高亮 + 提示文案。 */
    public void setRecording(boolean on) {
        if (voiceButton == null) return;
        voiceButton.setColorFilter(on ? t.brand : t.labelSecondary);
    }

    public String text() {
        return input.getText().toString();
    }

    public void clearText() {
        input.setText("");
    }

    public void appendText(String s) {
        if (s == null || s.isEmpty()) return;
        String cur = input.getText().toString();
        String out = cur.isEmpty() ? s : cur + (cur.endsWith(" ") || cur.endsWith("\n") ? "" : " ") + s;
        input.setText(out);
        input.setSelection(out.length());
    }

    public void focusInput() {
        input.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    public EditText editText() {
        return input;
    }

    /** 附件区：小卡片 + 删除。 */
    public void setFiles(List<Models.FileRef> files) {
        attachments.removeAllViews();
        if (files == null || files.isEmpty()) {
            attachments.setVisibility(GONE);
            return;
        }
        attachments.setVisibility(VISIBLE);
        for (Models.FileRef f : files) {
            LinearLayout box = Ui.row(getContext());
            box.setBackground(Ui.roundStroke(t.bgLayer2, t.borderL2, 1, 10));
            box.setPadding(Ui.dp(9), Ui.dp(6), Ui.dp(6), Ui.dp(6));
            box.addView(Ui.icon(getContext(), R.drawable.ic_file, t.labelTertiary, 14));
            TextView name = Ui.text(getContext(), f.name, 12, t.labelSecondary);
            name.setMaxWidth(Ui.dp(110));
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            nlp.leftMargin = Ui.dp(5);
            nlp.rightMargin = Ui.dp(4);
            box.addView(name, nlp);
            if ("uploading".equals(f.status)) {
                box.addView(Ui.icon(getContext(), R.drawable.ic_refresh, t.labelCaption, 12));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(8);
            attachments.addView(box, lp);
        }
    }
}
