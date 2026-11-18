package com.serein.chat.md;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import com.serein.chat.ui.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 Markdown 解析：块级（标题 / 段落 / 列表 / 代码块 / 引用 / 分隔线 / 表格）
 * 与行内（粗体 / 斜体 / 行内代码 / 删除线 / 链接）。只覆盖模型回答常用的子集。
 */
public final class Md {

    public static final int H1 = 1, H2 = 2, H3 = 3, H4 = 4, PARA = 5, LI = 6, CODE = 7,
            QUOTE = 8, HR = 9, TABLE = 10, SPACER = 11;

    public static final class Node {
        public int type;
        public String text = "";
        public String lang = "";
        public String marker = "";
        public int indent;
        public final List<String> cells = new ArrayList<>();
        public final List<List<String>> rows = new ArrayList<>();
    }

    private Md() {
    }

    // ------------------------------------------------------------ 块级解析

    public static List<Node> parse(String src) {
        List<Node> nodes = new ArrayList<>();
        if (src == null) return nodes;
        String[] lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        int n = lines.length;
        StringBuilder para = new StringBuilder();

        while (i < n) {
            String line = lines[i];

            // 围栏代码块（未闭合时吃到结尾，适配流式输出）
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                flushPara(nodes, para);
                String lang = trimmed.substring(3).trim();
                Node node = new Node();
                node.type = CODE;
                node.lang = lang;
                StringBuilder code = new StringBuilder();
                i++;
                while (i < n) {
                    String l = lines[i];
                    if (l.trim().startsWith("```")) {
                        i++;
                        break;
                    }
                    if (code.length() > 0) code.append('\n');
                    code.append(l);
                    i++;
                }
                node.text = code.toString();
                nodes.add(node);
                continue;
            }

            if (trimmed.isEmpty()) {
                flushPara(nodes, para);
                i++;
                continue;
            }

            // 分隔线
            if (trimmed.matches("^(-{3,}|\\*{3,}|_{3,})$")) {
                flushPara(nodes, para);
                Node hr = new Node();
                hr.type = HR;
                nodes.add(hr);
                i++;
                continue;
            }

            // 标题
            int hashes = 0;
            while (hashes < trimmed.length() && trimmed.charAt(hashes) == '#') hashes++;
            if (hashes >= 1 && hashes <= 6 && hashes < trimmed.length()
                    && trimmed.charAt(hashes) == ' ') {
                flushPara(nodes, para);
                Node h = new Node();
                h.type = hashes == 1 ? H1 : hashes == 2 ? H2 : hashes == 3 ? H3 : H4;
                h.text = trimmed.substring(hashes + 1).trim();
                nodes.add(h);
                i++;
                continue;
            }

            // 表格：当前行含 |，下一行是分隔行
            if (trimmed.contains("|") && i + 1 < n && isTableDivider(lines[i + 1])) {
                flushPara(nodes, para);
                Node table = new Node();
                table.type = TABLE;
                for (String cell : splitRow(lines[i])) table.cells.add(cell);
                i += 2;
                while (i < n && lines[i].trim().contains("|")) {
                    table.rows.add(splitRow(lines[i]));
                    i++;
                }
                nodes.add(table);
                continue;
            }

            // 引用（连续多行合并为一段）
            if (trimmed.startsWith(">")) {
                flushPara(nodes, para);
                StringBuilder quote = new StringBuilder();
                while (i < n && lines[i].trim().startsWith(">")) {
                    String q = lines[i].trim().substring(1);
                    if (q.startsWith(" ")) q = q.substring(1);
                    if (quote.length() > 0) quote.append('\n');
                    quote.append(q);
                    i++;
                }
                Node q = new Node();
                q.type = QUOTE;
                q.text = quote.toString();
                nodes.add(q);
                continue;
            }

            // 列表项
            String listMarker = listMarker(trimmed);
            if (listMarker != null) {
                flushPara(nodes, para);
                int indent = 0;
                int lead = 0;
                while (lead < line.length() && line.charAt(lead) == ' ') lead++;
                indent = lead >= 4 ? 2 : lead >= 2 ? 1 : 0;
                Node li = new Node();
                li.type = LI;
                li.indent = indent;
                li.marker = listMarker;
                li.text = trimmed.substring(listMarker.length()).trim();
                nodes.add(li);
                i++;
                continue;
            }

            if (para.length() > 0) para.append('\n');
            para.append(line);
            i++;
        }
        flushPara(nodes, para);
        return nodes;
    }

    private static boolean isTableDivider(String line) {
        String t = line.trim();
        if (!t.contains("-") || !t.contains("|")) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '|' && c != '-' && c != ':' && c != ' ') return false;
        }
        return true;
    }

    private static List<String> splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '|') {
                cur.append('|');
                i++;
            } else if (c == '|') {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().trim());
        return cells;
    }

    private static String listMarker(String trimmed) {
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            return trimmed.substring(0, 2);
        }
        int dot = trimmed.indexOf(". ");
        if (dot > 0 && dot <= 3) {
            boolean digits = true;
            for (int i = 0; i < dot; i++) {
                if (!Character.isDigit(trimmed.charAt(i))) {
                    digits = false;
                    break;
                }
            }
            if (digits) return trimmed.substring(0, dot + 2);
        }
        return null;
    }

    private static void flushPara(List<Node> nodes, StringBuilder para) {
        if (para.length() == 0) return;
        Node p = new Node();
        p.type = PARA;
        p.text = para.toString();
        nodes.add(p);
        para.setLength(0);
    }

    // ------------------------------------------------------------ 行内解析

    public static CharSequence inline(CharSequence src, Theme t) {
        return inline(src, t, 1f);
    }

    /** 生成带样式的文本；relSize 用于标题等整体缩放。 */
    public static CharSequence inline(CharSequence src, Theme t, float relSize) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (src == null) return out;
        String s = src.toString();
        int i = 0;
        int n = s.length();

        while (i < n) {
            char c = s.charAt(i);

            // 转义
            if (c == '\\' && i + 1 < n) {
                int start = out.length();
                out.append(s.charAt(i + 1));
                i += 2;
                applyRel(out, start, out.length(), relSize);
                continue;
            }

            // 行内代码
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i) {
                    String code = s.substring(i + 1, end).replace("\n", " ");
                    int start = out.length();
                    out.append(code);
                    int e = out.length();
                    out.setSpan(new TypefaceSpan("monospace"), start, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(t.inlineCode), start, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(t.labelPrimary), start, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.92f * relSize), start, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }

            // 粗体 / 斜体 / 删除线
            if ((c == '*' || c == '_' || c == '~') && i + 1 < n) {
                boolean dbl = i + 1 < n && s.charAt(i + 1) == c;
                boolean strike = c == '~' && dbl;
                if (dbl || c != '~') {
                    String delim = dbl ? "" + c + c : "" + c;
                    int end = s.indexOf(delim, i + delim.length());
                    if (end > i + delim.length()) {
                        String inner = s.substring(i + delim.length(), end);
                        int start = out.length();
                        out.append(inline(inner, t, relSize));
                        int e = out.length();
                        if (strike) {
                            out.setSpan(new StrikethroughSpan(), start, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        } else if (dbl) {
                            out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, e,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        } else {
                            out.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), start, e,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        i = end + delim.length();
                        continue;
                    }
                }
            }

            // 链接 [text](url)
            if (c == '[') {
                int close = s.indexOf(']', i + 1);
                if (close > i && close + 1 < n && s.charAt(close + 1) == '(') {
                    int paren = s.indexOf(')', close + 2);
                    if (paren > close) {
                        String label = s.substring(i + 1, close);
                        String url = s.substring(close + 2, paren).trim();
                        int start = out.length();
                        out.append(inline(label, t, relSize));
                        int e = out.length();
                        out.setSpan(new URLSpan(url), start, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new ForegroundColorSpan(t.brandText), start, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = paren + 1;
                        continue;
                    }
                }
            }

            // 裸链接
            if ((c == 'h' || c == 'H') && (s.startsWith("http://", i) || s.startsWith("https://", i))) {
                int j = i;
                while (j < n && !Character.isWhitespace(s.charAt(j)) && s.charAt(j) != ')' && s.charAt(j) != ']') j++;
                String url = s.substring(i, j);
                int start = out.length();
                out.append(url);
                int e = out.length();
                out.setSpan(new URLSpan(url), start, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(t.brandText), start, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = j;
                continue;
            }

            int start = out.length();
            out.append(c);
            if (relSize != 1f) applyRel(out, start, out.length(), relSize);
            i++;
        }
        return out;
    }

    private static void applyRel(SpannableStringBuilder out, int start, int end, float relSize) {
        if (relSize != 1f && end > start) {
            out.setSpan(new RelativeSizeSpan(relSize), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** 去掉行内标记，用于标题栏摘要等纯文本场景。 */
    public static String plain(String src) {
        if (src == null) return "";
        return src.replaceAll("\\*\\*|__|~~|`", "").replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
    }
}
