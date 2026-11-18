package com.serein.chat.ui;

import android.content.Context;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import com.serein.chat.R;

/**
 * 动画工具：刷新率自适应时长、流式文字渐映、按压弹性反馈、淡入过渡。
 * 时长按屏幕刷新率换算，120Hz 设备上更跟手，60Hz 设备上不抖。
 */
public final class Anim {

    private static float refreshRate = 60f;

    private Anim() {
    }

    public static void init(Context c) {
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                float rate = wm.getDefaultDisplay().getRefreshRate();
                if (rate >= 30f && rate <= 240f) refreshRate = rate;
            }
        } catch (Throwable ignored) {
        }
    }

    public static float refreshRate() {
        return refreshRate;
    }

    /** 单帧时长（毫秒），按刷新率换算。 */
    public static int frameMs() {
        return Math.max(4, Math.round(1000f / refreshRate));
    }

    /** 流式重绘间隔：贴近单帧，避免"一跳一跳"的观感。 */
    public static int streamIntervalMs() {
        return Math.max(16, Math.min(24, frameMs()));
    }

    /** 由刷新率换算的短动画时长。 */
    public static int quick() {
        return Math.max(90, Math.round(140f * 60f / refreshRate));
    }

    public static int normal() {
        return Math.max(160, Math.round(240f * 60f / refreshRate));
    }

    /** 按下缩小 + 回弹，所有可点区域统一的“灵动”反馈。 */
    public static void attachSpringPress(View v) {
        if (v == null || v.getTag(R.id.spring_press_tag) != null) return;
        v.setTag(R.id.spring_press_tag, Boolean.TRUE);
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().cancel();
                    view.animate().scaleX(0.94f).scaleY(0.94f)
                            .setDuration(frameMs() * 3L)
                            .setInterpolator(new DecelerateInterpolator(1.6f))
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().cancel();
                    view.animate().scaleX(1f).scaleY(1f)
                            .setDuration(Math.round(normal() * 1.1f))
                            .setInterpolator(new OvershootInterpolator(2.2f))
                            .start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** 流式文字渐映：把 [fadeFrom, end) 的文字从透明淡入到目标色。 */
    public static void reveal(TextView tv, CharSequence text, int fadeFrom, int baseColor) {
        if (tv == null) return;
        if (fadeFrom < 0 || fadeFrom >= text.length()) {
            tv.setText(text);
            return;
        }
        Object running = tv.getTag(R.id.fade_anim_tag);
        if (running instanceof android.animation.Animator) {
            ((android.animation.Animator) running).cancel();
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        FadeSpan span = new FadeSpan(baseColor);
        sb.setSpan(span, fadeFrom, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(sb);
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(normal());
        anim.addUpdateListener(a -> {
            span.progress = (float) a.getAnimatedValue();
            tv.invalidate();
        });
        anim.start();
        tv.setTag(R.id.fade_anim_tag, anim);
    }

    /**
     * 追加式渐映：只把新增的文字 append 到 TextView 尾部，
     * 不重建整段 Spannable，长段落流式时不会反复触发全量排版。
     */
    public static void appendFade(TextView tv, CharSequence delta, int baseColor) {
        if (tv == null || delta == null || delta.length() == 0) return;
        SpannableStringBuilder sb = new SpannableStringBuilder(delta);
        FadeSpan span = new FadeSpan(baseColor);
        sb.setSpan(span, 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.append(sb);
        Object running = tv.getTag(R.id.fade_anim_tag);
        if (running instanceof android.animation.Animator) {
            ((android.animation.Animator) running).cancel();
        }
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(normal());
        anim.addUpdateListener(a -> {
            span.progress = (float) a.getAnimatedValue();
            tv.invalidate();
        });
        anim.start();
        tv.setTag(R.id.fade_anim_tag, anim);
    }

    /** 单段文字的透明度着色，随动画进度变实。 */
    private static final class FadeSpan extends CharacterStyle {
        private final int baseColor;
        float progress;

        FadeSpan(int baseColor) {
            this.baseColor = baseColor;
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            int alpha = Math.round(android.graphics.Color.alpha(baseColor) * progress);
            tp.setColor(android.graphics.Color.argb(alpha,
                    android.graphics.Color.red(baseColor),
                    android.graphics.Color.green(baseColor),
                    android.graphics.Color.blue(baseColor)));
        }
    }

    /** 子页面进入：淡入 + 轻微上浮。 */
    public static void enter(View v) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(12));
        v.animate().alpha(1f).translationY(0f).setDuration(normal())
                .setInterpolator(new DecelerateInterpolator(1.4f)).start();
    }

    /** 内容切换：淡出旧内容再淡入新内容，避免硬切。 */
    public static void crossFade(View v, Runnable swap) {
        if (v == null) {
            swap.run();
            return;
        }
        v.animate().alpha(0f).setDuration(Math.round(quick() * 0.7f)).withEndAction(() -> {
            swap.run();
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(normal()).start();
        }).start();
    }

    public static void popIn(View v) {
        if (v == null) return;
        v.setScaleX(0.92f);
        v.setScaleY(0.92f);
        v.setAlpha(0f);
        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(normal())
                .setInterpolator(new OvershootInterpolator(1.8f))
                .start();
    }
}
