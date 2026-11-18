package com.serein.chat.md;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.serein.chat.R;
import com.serein.chat.ui.Anim;
import com.serein.chat.ui.Theme;
import com.serein.chat.ui.Ui;

import java.util.List;

/** 把 Markdown 渲染成一组原生视图；支持流式增量重绘。 */
public final class MarkdownView extends LinearLayout {

    private final Theme t;
    private final String codeFont = "monospace";
    private String lastText;
    private java.util.List<Md.Node> lastNodes;
    private TextView tailView;
    private int tailLen;
    private TextView lastBuiltText;

    public MarkdownView(Context c, Theme t) {
        super(c);
        this.t = t;
        setOrientation(VERTICAL);
    }

    /**
     * 流式渲染：文本未变化直接跳过；若只是末尾文本增长且结构不变，
     * 只更新最后一段并给新增字符做渐映，避免整段重建造成的闪烁。
     */
    public boolean render(String markdown) {
        String text = markdown == null ? "" : markdown;
        if (text.equals(lastText)) return false;
        List<Md.Node> nodes = Md.parse(text);
        // 只新增了末尾节点：保留已有视图，仅构建新增部分（长回复流式时的主要优化）
        if (lastNodes != null && lastText != null && text.startsWith(lastText)
                && nodes.size() > lastNodes.size()
                && samePrefix(nodes, lastNodes, lastNodes.size() - 1)) {
            int keep = Math.max(0, lastNodes.size() - 1);
            while (getChildCount() > keep) removeViewAt(getChildCount() - 1);
            for (int i = keep; i < nodes.size(); i++) {
                Md.Node node = nodes.get(i);
                lastBuiltText = null;
                View v = build(node);
                addView(v);
                if (i == nodes.size() - 1) {
                    tailView = lastBuiltText != null ? lastBuiltText
                            : (v instanceof TextView ? (TextView) v : null);
                    tailLen = node.text.length();
                }
            }
            lastText = text;
            lastNodes = nodes;
            return true;
        }

        boolean append = lastText != null && text.startsWith(lastText) && lastNodes != null
                && nodes.size() == lastNodes.size() && sameTypes(nodes, lastNodes);
        if (append && tailView != null && !nodes.isEmpty() && tailLen > 0) {
            Md.Node tail = nodes.get(nodes.size() - 1);
            Md.Node prevTail = lastNodes.get(lastNodes.size() - 1);
            String prev = prevTail.text == null ? "" : prevTail.text;
            String cur = tail.text == null ? "" : tail.text;
            if (isTextNode(tail) && cur.startsWith(prev) && cur.length() > prev.length()) {
                // 纯尾部追加：只 append 增量，避免整段重排
                Anim.appendFade(tailView, cur.substring(prev.length()), t.labelPrimary);
                tailLen = cur.length();
                lastText = text;
                lastNodes = nodes;
                return true;
            }
            if (isTextNode(tail) && cur.length() > tailLen) {
                Anim.reveal(tailView, tailText(tail), tailLen, t.labelPrimary);
                tailLen = cur.length();
                lastText = text;
                lastNodes = nodes;
                return true;
            }
        }
        rebuild(nodes, append ? (lastNodes == null ? 0 : tailLenOf(lastNodes)) : 0);
        lastText = text;
        lastNodes = nodes;
        return true;
    }

    private boolean isTextNode(Md.Node n) {
        return n.type == Md.PARA || n.type == Md.LI || n.type == Md.QUOTE
                || (n.type >= Md.H1 && n.type <= Md.H4);
    }

    private int tailLenOf(List<Md.Node> nodes) {
        return nodes.isEmpty() ? 0 : nodes.get(nodes.size() - 1).text.length();
    }

    private boolean samePrefix(List<Md.Node> a, List<Md.Node> b, int count) {
        for (int i = 0; i < count; i++) {
            if (a.get(i).type != b.get(i).type) return false;
            String ta = a.get(i).text == null ? "" : a.get(i).text;
            String tb = b.get(i).text == null ? "" : b.get(i).text;
            if (!ta.equals(tb)) return false;
        }
        return true;
    }

    private boolean sameTypes(List<Md.Node> a, List<Md.Node> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).type != b.get(i).type) return false;
        }
        return true;
    }

    private CharSequence tailText(Md.Node node) {
        if (node.type == Md.LI) {
            String bullet = node.marker.endsWith(". ")
                    ? node.marker.substring(0, node.marker.length() - 2) + ". " : "• ";
            return android.text.SpannableStringBuilder.valueOf(bullet).append(Md.inline(node.text, t));
        }
        return Md.inline(node.text, t);
    }

    private void rebuild(List<Md.Node> nodes, int fadeFrom) {
        removeAllViews();
        tailView = null;
        tailLen = 0;
        for (int i = 0; i < nodes.size(); i++) {
            Md.Node node = nodes.get(i);
            lastBuiltText = null;
            View v = build(node);
            addView(v);
            if (i == nodes.size() - 1) {
                tailView = lastBuiltText != null ? lastBuiltText : (v instanceof TextView ? (TextView) v : null);
                tailLen = node.text.length();
                if (fadeFrom > 0 && tailView != null && isTextNode(node) && node.text.length() > fadeFrom) {
                    Anim.reveal(tailView, tailText(node), fadeFrom, t.labelPrimary);
                }
            }
        }
    }

    private View build(Md.Node node) {
        switch (node.type) {
            case Md.CODE:
                return codeBlock(node);
            case Md.HR:
                return hr();
            case Md.TABLE:
                return table(node);
            case Md.SPACER:
                return spacer(8);
            default:
                return textBlock(node);
        }
    }

    private View textBlock(Md.Node node) {
        TextView tv = new TextView(getContext());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSize(node));
        tv.setTextColor(node.type == Md.QUOTE ? t.labelSecondary : t.labelPrimary);
        tv.setIncludeFontPadding(false);
        tv.setLineSpacing(Ui.dp(4), 1f);
        tv.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        tv.setTextIsSelectable(true);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setHighlightColor(Ui.alpha(t.brand, 0.22f));

        lastBuiltText = tv;
        if (node.type == Md.LI) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            String bullet = node.marker.endsWith(". ") ? node.marker.substring(0, node.marker.length() - 2) + ". " : "• ";
            sb.append(bullet);
            sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(Md.inline(node.text, t));
            tv.setText(sb);
            int pad = Ui.dp(node.indent * 20);
            tv.setPadding(pad + Ui.dp(bullet.endsWith(". ") ? 0 : 2), Ui.dp(2), 0, Ui.dp(2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(2);
            tv.setLayoutParams(lp);
            return tv;
        }

        if (node.type == Md.QUOTE) {
            LinearLayout wrap = new LinearLayout(getContext());
            wrap.setOrientation(HORIZONTAL);
            View bar = new View(getContext());
            bar.setBackgroundColor(t.borderL2);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(3), LayoutParams.MATCH_PARENT);
            bar.setLayoutParams(blp);
            wrap.addView(bar);
            tv.setText(Md.inline(node.text, t));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            tlp.leftMargin = Ui.dp(10);
            tv.setLayoutParams(tlp);
            wrap.addView(tv);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            wlp.bottomMargin = Ui.dp(10);
            wrap.setLayoutParams(wlp);
            return wrap;
        }

        float sizeSp = baseSize(node);
        if (node.type == Md.H1 || node.type == Md.H2 || node.type == Md.H3) {
            tv.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        } else if (node.type == Md.H4) {
            tv.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }
        tv.setText(Md.inline(node.text, t));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(node.type >= Md.H1 && node.type <= Md.H4 ? 12 : 0);
        lp.bottomMargin = Ui.dp(node.type >= Md.H1 && node.type <= Md.H4 ? 6 : 10);
        tv.setLayoutParams(lp);
        if (sizeSp < 16f) tv.setLineSpacing(Ui.dp(3), 1f);
        return tv;
    }

    private float baseSize(Md.Node node) {
        switch (node.type) {
            case Md.H1:
                return 24f;
            case Md.H2:
                return 22f;
            case Md.H3:
                return 20f;
            case Md.H4:
                return 16f;
            default:
                return 16f;
        }
    }

    private View codeBlock(Md.Node node) {
        LinearLayout wrap = new LinearLayout(getContext());
        wrap.setOrientation(VERTICAL);
        android.graphics.drawable.GradientDrawable bg = Ui.round(t.codeBlock, 12);
        wrap.setBackground(bg);
        wrap.setClipToOutline(true);
        wrap.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.dp(12));
            }
        });

        // 顶部条：语言 + 复制
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(8), Ui.dp(6));

        TextView lang = new TextView(getContext());
        lang.setText(node.lang == null || node.lang.isEmpty() ? "code" : node.lang);
        lang.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lang.setTextColor(t.labelTertiary);
        lang.setIncludeFontPadding(false);
        header.addView(lang, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout copy = new LinearLayout(getContext());
        copy.setOrientation(HORIZONTAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setPadding(Ui.dp(8), Ui.dp(3), Ui.dp(8), Ui.dp(3));
        ImageView ic = Ui.icon(getContext(), R.drawable.ic_copy, t.labelTertiary, 13);
        copy.addView(ic);
        TextView ct = new TextView(getContext());
        ct.setText(getContext().getString(R.string.copy));
        ct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ct.setTextColor(t.labelTertiary);
        ct.setIncludeFontPadding(false);
        LinearLayout.LayoutParams ctlp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        ctlp.leftMargin = Ui.dp(5);
        copy.addView(ct, ctlp);
        Ui.pressable(copy, 0x00000000, 8, t.hoverAccent);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                Ui.copy(getContext(), "code", node.text);
                Ui.toast(getContext(), getContext().getString(R.string.copied));
            }
        });
        header.addView(copy);
        wrap.addView(header);

        View line = new View(getContext());
        line.setBackgroundColor(t.borderL1);
        line.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.5f))));
        wrap.addView(line);

        HorizontalScrollView hs = new HorizontalScrollView(getContext());
        hs.setHorizontalScrollBarEnabled(false);
        TextView code = new TextView(getContext());
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        code.setTypeface(Typeface.create(codeFont, Typeface.NORMAL));
        code.setTextColor(t.labelPrimary);
        code.setIncludeFontPadding(false);
        code.setLineSpacing(Ui.dp(4), 1f);
        code.setTextIsSelectable(true);
        code.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(12));
        code.setText(highlight(node.text, node.lang));
        hs.addView(code, new HorizontalScrollView.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        wrap.addView(hs);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(12);
        lp.topMargin = Ui.dp(2);
        wrap.setLayoutParams(lp);
        return wrap;
    }

    /** 极简语法着色：注释 / 字符串 / 关键字，覆盖常见语言。 */
    private CharSequence highlight(String code, String lang) {
        SpannableStringBuilder sb = new SpannableStringBuilder(code == null ? "" : code);
        int len = sb.length();
        if (len == 0) return sb;
        int commentColor = t.labelTertiary;
        int stringColor = t.dark ? 0xFF9FE6A0 : 0xFF2E7D32;
        int keywordColor = t.dark ? 0xFF8AB4F8 : 0xFF1A56DB;
        int numberColor = t.dark ? 0xFFE6C07B : 0xFFB45309;

        // 字符串
        int i = 0;
        while (i < len) {
            char c = sb.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                int j = i + 1;
                while (j < len) {
                    char d = sb.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == c) {
                        j++;
                        break;
                    }
                    if (d == '\n' && c != '`') break;
                    j++;
                }
                sb.setSpan(new ForegroundColorSpan(stringColor), i, Math.min(j, len),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = j;
                continue;
            }
            i++;
        }
        // 注释
        String[] lineComments = {"//", "#"};
        for (String lc : lineComments) {
            int idx = 0;
            while ((idx = code.indexOf(lc, idx)) >= 0) {
                boolean inString = false;
                for (Object span : sb.getSpans(idx, idx + 1, ForegroundColorSpan.class)) {
                    if (sb.getSpanStart(span) <= idx && sb.getSpanEnd(span) > idx) inString = true;
                }
                int eol = code.indexOf('\n', idx);
                if (eol < 0) eol = len;
                if (!inString) {
                    sb.setSpan(new ForegroundColorSpan(commentColor), idx, eol, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                idx = eol;
            }
        }
        // 关键字
        if (lang != null && !lang.isEmpty()) {
            for (String kw : keywords(lang.toLowerCase())) {
                int idx = 0;
                while ((idx = code.indexOf(kw, idx)) >= 0) {
                    boolean leftOk = idx == 0 || !isWord(code.charAt(idx - 1));
                    int end = idx + kw.length();
                    boolean rightOk = end >= len || !isWord(code.charAt(end));
                    boolean plain = true;
                    for (Object span : sb.getSpans(idx, idx + 1, ForegroundColorSpan.class)) {
                        if (sb.getSpanStart(span) <= idx && sb.getSpanEnd(span) > idx) plain = false;
                    }
                    if (leftOk && rightOk && plain) {
                        sb.setSpan(new ForegroundColorSpan(keywordColor), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    idx = end;
                }
            }
        }
        // 数字
        int k = 0;
        while (k < len) {
            if (Character.isDigit(sb.charAt(k))) {
                int j = k;
                while (j < len && (Character.isDigit(sb.charAt(j)) || sb.charAt(j) == '.')) j++;
                boolean plain = true;
                for (Object span : sb.getSpans(k, k + 1, ForegroundColorSpan.class)) {
                    if (sb.getSpanStart(span) <= k && sb.getSpanEnd(span) > k) plain = false;
                }
                if (plain) sb.setSpan(new ForegroundColorSpan(numberColor), k, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                k = j;
                continue;
            }
            k++;
        }
        return sb;
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static String[] keywords(String lang) {
        if (lang.startsWith("java")) {
            return new String[]{"public", "private", "protected", "class", "interface", "extends", "implements",
                    "static", "final", "void", "int", "long", "double", "float", "boolean", "char", "return",
                    "if", "else", "for", "while", "switch", "case", "new", "try", "catch", "finally", "throw",
                    "throws", "import", "package", "this", "super", "null", "true", "false", "abstract", "synchronized"};
        }
        if (lang.startsWith("kotlin")) {
            return new String[]{"fun", "val", "var", "class", "object", "interface", "data", "sealed", "when",
                    "if", "else", "for", "while", "return", "null", "true", "false", "import", "package",
                    "suspend", "override", "private", "public", "internal", "companion"};
        }
        if (lang.startsWith("python") || lang.equals("py")) {
            return new String[]{"def", "class", "return", "if", "elif", "else", "for", "while", "import", "from",
                    "as", "with", "try", "except", "finally", "raise", "lambda", "None", "True", "False", "yield",
                    "async", "await", "pass", "in", "not", "and", "or", "global"};
        }
        if (lang.startsWith("js") || lang.startsWith("javascript") || lang.startsWith("ts")
                || lang.startsWith("typescript")) {
            return new String[]{"function", "const", "let", "var", "return", "if", "else", "for", "while",
                    "class", "extends", "new", "typeof", "instanceof", "async", "await", "try", "catch",
                    "finally", "throw", "import", "export", "default", "from", "null", "undefined", "true", "false"};
        }
        if (lang.startsWith("json")) {
            return new String[]{"true", "false", "null"};
        }
        if (lang.startsWith("sh") || lang.startsWith("bash") || lang.startsWith("shell")) {
            return new String[]{"echo", "cd", "export", "if", "then", "fi", "for", "do", "done", "while",
                    "function", "return", "sudo", "apt", "npm", "yarn", "git"};
        }
        if (lang.startsWith("sql")) {
            return new String[]{"SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE",
                    "JOIN", "GROUP", "ORDER", "BY", "LIMIT", "AND", "OR", "NULL", "VALUES", "SET"};
        }
        return new String[0];
    }

    private View hr() {
        View v = new View(getContext());
        v.setBackgroundColor(t.borderL2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.5f)));
        lp.topMargin = Ui.dp(10);
        lp.bottomMargin = Ui.dp(12);
        v.setLayoutParams(lp);
        return v;
    }

    private View spacer(int dp) {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(dp)));
        return v;
    }

    /** 表格：等宽单元格 + 表头底色，横向不滚动，窄屏自动换行。 */
    private View table(Md.Node node) {
        LinearLayout wrap = new LinearLayout(getContext());
        wrap.setOrientation(VERTICAL);
        wrap.setBackground(Ui.roundStroke(t.bgBase, t.borderL2, 1, 10));
        int cols = Math.max(1, node.cells.size());

        wrap.addView(tableRow(node.cells, cols, true));
        for (List<String> r : node.rows) {
            View d = new View(getContext());
            d.setBackgroundColor(t.borderL1);
            d.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.5f))));
            wrap.addView(d);
            wrap.addView(tableRow(r, cols, false));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(12);
        lp.topMargin = Ui.dp(4);
        wrap.setLayoutParams(lp);
        return wrap;
    }

    private View tableRow(List<String> cells, int cols, boolean header) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        for (int i = 0; i < cols; i++) {
            TextView tv = new TextView(getContext());
            String cell = i < cells.size() ? cells.get(i) : "";
            tv.setText(Md.inline(cell, t));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setTextColor(header ? t.labelPrimary : t.labelSecondary);
            tv.setIncludeFontPadding(false);
            tv.setLineSpacing(Ui.dp(3), 1f);
            tv.setPadding(Ui.dp(9), Ui.dp(7), Ui.dp(9), Ui.dp(7));
            if (header) tv.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            if (header) tv.setBackgroundColor(t.codeBanner);
            row.addView(tv, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }
}
