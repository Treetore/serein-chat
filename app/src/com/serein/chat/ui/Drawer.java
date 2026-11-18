package com.serein.chat.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.serein.chat.R;
import com.serein.chat.data.Models;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** 侧边栏：开启新对话、按时间分组的历史会话、设置入口。 */
public final class Drawer extends LinearLayout {

    public interface Host {
        void onNewChat();

        void onOpenSession(Models.Session s);

        void onRenameSession(Models.Session s);

        void onDeleteSession(Models.Session s);

        void onSettings();

        void onCloseDrawer();
    }

    private final Theme t;
    private final Host host;
    private final LinearLayout listBox;
    private final int widthPx;
    private final java.util.List<Models.Session> all = new ArrayList<>();
    private String activeId;
    private String query = "";

    public Drawer(Context c, Theme t, Host host) {
        super(c);
        this.t = t;
        this.host = host;
        setOrientation(VERTICAL);
        setBackgroundColor(t.sidebar);
        setClickable(true);

        widthPx = Math.min(Ui.dp(310), (int) (c.getResources().getDisplayMetrics().widthPixels * 0.84f));
        setLayoutParams(new android.widget.FrameLayout.LayoutParams(widthPx,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        // 顶部
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(VERTICAL);
        top.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(6));

        LinearLayout newChat = Ui.row(c);
        newChat.setPadding(Ui.dp(12), Ui.dp(11), Ui.dp(12), Ui.dp(11));
        Ui.pressable(newChat, Ui.round(t.sidebarActive, 12), 12, t.hoverAccent);
        newChat.addView(Ui.icon(c, R.drawable.ic_new_chat, t.labelPrimary, 18));
        TextView nct = Ui.text(c, c.getString(R.string.new_chat), 15, t.labelPrimary);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = Ui.dp(10);
        newChat.addView(nct, nlp);
        newChat.setOnClickListener(v -> host.onNewChat());
        top.addView(newChat, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // 搜索历史会话
        LinearLayout searchBox = Ui.row(c);
        searchBox.setPadding(Ui.dp(10), Ui.dp(1), Ui.dp(10), Ui.dp(1));
        searchBox.setBackground(Ui.roundStroke(t.bgBase, t.borderL1, 1, 10));
        searchBox.addView(Ui.icon(c, R.drawable.ic_search, t.labelCaption, 15));
        android.widget.EditText search = new android.widget.EditText(c);
        search.setBackground(null);
        search.setHint(R.string.search_sessions);
        search.setHintTextColor(t.labelCaption);
        search.setTextColor(t.labelPrimary);
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        search.setSingleLine(true);
        search.setPadding(Ui.dp(8), Ui.dp(8), 0, Ui.dp(8));
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int count) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int count) {
                query = s == null ? "" : s.toString().trim();
                renderList();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        searchBox.addView(search, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        searchLp.topMargin = Ui.dp(4);
        top.addView(searchBox, searchLp);
        addView(top);

        // 列表
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        listBox = new LinearLayout(c);
        listBox.setOrientation(VERTICAL);
        listBox.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), Ui.dp(12));
        scroll.addView(listBox, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部设置
        View line = new View(c);
        line.setBackgroundColor(t.borderL2);
        addView(line, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.5f))));

        LinearLayout settings = Ui.row(c);
        settings.setPadding(Ui.dp(22), Ui.dp(16), Ui.dp(22), Ui.dp(16) + Ui.dp(6));
        Ui.pressable(settings, t.sidebar, 0, t.hoverAccent);
        settings.addView(Ui.icon(c, R.drawable.ic_tune, t.labelSecondary, 18));
        TextView st = Ui.text(c, c.getString(R.string.settings), 15, t.labelPrimary);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.dp(12);
        settings.addView(st, slp);
        settings.setOnClickListener(v -> host.onSettings());
        addView(settings);
    }

    public int panelWidth() {
        return widthPx;
    }

    public void setSessions(List<Models.Session> sessions, String activeId) {
        all.clear();
        if (sessions != null) all.addAll(sessions);
        this.activeId = activeId;
        renderList();
    }

    private void renderList() {
        listBox.removeAllViews();
        List<Models.Session> matched = new ArrayList<>();
        for (Models.Session s : all) {
            if (query.isEmpty()) {
                matched.add(s);
            } else {
                String title = s.title == null ? "" : s.title.toLowerCase();
                if (title.contains(query.toLowerCase())) matched.add(s);
            }
        }
        if (!query.isEmpty() && matched.isEmpty()) {
            TextView none = Ui.text(getContext(), getContext().getString(R.string.search_no_result),
                    13, t.labelCaption);
            none.setPadding(Ui.dp(12), Ui.dp(14), 0, 0);
            listBox.addView(none);
            return;
        }
        renderSessions(matched);
    }

    private void renderSessions(List<Models.Session> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            TextView empty = Ui.text(getContext(), getContext().getString(R.string.no_sessions), 13, t.labelCaption);
            empty.setPadding(Ui.dp(12), Ui.dp(14), 0, 0);
            listBox.addView(empty);
            return;
        }
        long now = System.currentTimeMillis();
        long startOfToday = startOfToday();
        String lastGroup = null;
        for (Models.Session s : sessions) {
            String group = groupOf(s.updatedAt, startOfToday, now);
            if (!group.equals(lastGroup)) {
                TextView header = Ui.text(getContext(), group, 12, t.labelCaption);
                header.setPadding(Ui.dp(12), Ui.dp(14), 0, Ui.dp(6));
                listBox.addView(header);
                lastGroup = group;
            }
            listBox.addView(item(s, s.id.equals(activeId)));
        }
    }

    private View item(Models.Session s, boolean active) {
        LinearLayout row = Ui.row(getContext());
        row.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(6), Ui.dp(10));
        Ui.pressable(row, Ui.round(active ? t.sidebarActive : 0x00000000, 10), 10, t.hoverAccent);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(1);
        row.setLayoutParams(lp);

        TextView title = Ui.text(getContext(), s.title == null || s.title.isEmpty()
                ? getContext().getString(R.string.untitled_chat) : s.title,
                14, t.labelPrimary);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        ImageView more = new ImageView(getContext());
        more.setImageResource(R.drawable.ic_more);
        more.setColorFilter(t.labelCaption);
        int size = Ui.dp(28);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(size, size);
        more.setLayoutParams(mlp);
        more.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        Ui.pressable(more, Ui.circle(0x00000000), 14, t.hoverAccent);
        more.setOnClickListener(v -> showItemMenu(v, s));
        row.addView(more);

        row.setOnClickListener(v -> host.onOpenSession(s));
        return row;
    }

    private void showItemMenu(View anchor, Models.Session s) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(getContext(), anchor);
        menu.getMenu().add(0, 1, 0, getContext().getString(R.string.rename));
        menu.getMenu().add(0, 2, 1, getContext().getString(R.string.delete));
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) host.onRenameSession(s);
            else host.onDeleteSession(s);
            return true;
        });
        menu.show();
    }

    private long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private String groupOf(long updated, long startOfToday, long now) {
        Context c = getContext();
        if (updated >= startOfToday) return c.getString(R.string.session_today);
        if (updated >= startOfToday - 86400000L) return c.getString(R.string.session_yesterday);
        if (updated >= now - 7 * 86400000L) return c.getString(R.string.session_7days);
        if (updated >= now - 30 * 86400000L) return c.getString(R.string.session_30days);
        return c.getString(R.string.earlier_group);
    }
}
