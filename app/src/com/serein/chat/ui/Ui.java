package com.serein.chat.ui;

import android.content.Context;

import com.serein.chat.R;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 视图构建与绘制的小工具集合。 */
public final class Ui {

    private Ui() {
    }

    public static float density = 2f;
    /** 最近一次系统栏/刘海/输入法 insets，浮层复用以避开导航条。 */
    public static int insetBottom;
    public static int insetTop;
    private static Context appContext;

    public static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        appContext = c.getApplicationContext();
        Anim.init(c);
    }

    public static int dp(float v) {
        return Math.round(v * density);
    }

    public static int sp(float v) {
        return Math.round(v * density);
    }

    public static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    public static GradientDrawable roundStroke(int fill, int strokeColor, float strokeWidthDp, float radiusDp) {
        GradientDrawable d = round(fill, radiusDp);
        d.setStroke(Math.max(1, dp(strokeWidthDp)), strokeColor);
        return d;
    }

    public static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** 给视图加上圆角按压反馈（RippleDrawable，API21+）。 */
    /** 复制到剪贴板，并标记为敏感内容（系统不展示预览）。 */
    public static void copy(Context c, String label, String text) {
        if (text == null || text.isEmpty()) return;
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            android.content.ClipData clip =
                    android.content.ClipData.newPlainText(label, text);
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
            cm.setPrimaryClip(clip);
        } catch (Throwable ignored) {
        }
        toast(c, c.getString(R.string.copied));
    }

    public static void pressable(View v, Drawable content, float radiusDp, int rippleColor) {
        GradientDrawable mask = round(0xFFFFFFFF, radiusDp);
        RippleDrawable rd = new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
        v.setBackground(rd);
        Anim.attachSpringPress(v);
    }

    public static void pressable(View v, int fill, float radiusDp, int rippleColor) {
        pressable(v, round(fill, radiusDp), radiusDp, rippleColor);
    }

    public static ImageView icon(Context c, int resId, int color, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(resId);
        iv.setColorFilter(color);
        int s = dp(sizeDp);
        iv.setLayoutParams(new ViewGroup.LayoutParams(s, s));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    public static ImageView icon(Context c, int resId, int color, int sizeDp, int paddingDp) {
        ImageView iv = icon(c, resId, color, sizeDp);
        int p = dp(paddingDp);
        iv.setPadding(p / 2, p / 2, p / 2, p / 2);
        return iv;
    }

    /** 44dp 命中区的图标按钮，对应 --ds-control-height-xl。 */
    public static ImageView iconButton(Context c, int resId, int color, Theme t) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(resId);
        iv.setColorFilter(color);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(10);
        iv.setPadding(pad, pad, pad, pad);
        int size = dp(40);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        iv.setLayoutParams(lp);
        pressable(iv, circle(t.hover), 20, t.hoverAccent);
        iv.setClickable(true);
        iv.setFocusable(true);
        return iv;
    }

    public static TextView text(Context c, CharSequence s, float sizeSp, int color) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    public static TextView label(Context c, CharSequence s, float sizeSp, int color) {
        TextView tv = text(c, s, sizeSp, color);
        tv.setLineSpacing(dp(2), 1f);
        return tv;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static FrameLayout.LayoutParams frameMatch() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    public static void margin(View v, int l, int tp, int r, int b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) p).setMargins(dp(l), dp(tp), dp(r), dp(b));
        }
    }

    public static void margins(View v, int l, int tp, int r, int b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) p).setMargins(l, tp, r, b);
        }
    }

    public static View divider(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
        return v;
    }

    public static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            int h = c.getResources().getDimensionPixelSize(id);
            if (h > 0) return h;
        }
        return dp(24);
    }

    public static int navBarHeight(Context c) {
        int id = c.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (id > 0) return c.getResources().getDimensionPixelSize(id);
        return dp(48);
    }

    public static void toast(Context c, CharSequence msg) {
        if (c == null) c = appContext;
        if (c == null) return;
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    public static int alpha(int color, float a) {
        int al = Math.round(Color.alpha(color) * a);
        return Color.argb(al, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static void setLightStatusBar(android.view.Window w, boolean light) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController c = null;
            try {
                c = w.getInsetsController();
            } catch (Throwable ignored) {
                // 装饰视图尚未挂载时不可用，等下次设置
            }
            if (c != null) {
                c.setSystemBarsAppearance(light ? android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            View decor = w.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    public static void fadeIn(View v) {
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(160).start();
    }

    public static CharSequence ellipsize(TextView tv) {
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setSingleLine(true);
        return tv.getText();
    }
}
