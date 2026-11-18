package com.serein.chat.data;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * DeepSeek 官方定价（元 / 百万 tokens）。
 *
 * 高峰时段：北京时间周一至周五（不含法定节假日）9:00–12:00、14:00–18:00；
 * 其余时段（含周末）为空闲时段，价格为高峰的一半。
 *
 * 注：法定节假日不做判断（只按周一至周五 + 时段区分），因此节假日的估算会略高。
 */
public final class Price {

    /** 每百万 tokens 单价（元）。 */
    private static final double[][] FLASH = {
            {0.02, 1, 4},    // 空闲：缓存命中 / 未命中 / 输出
            {0.04, 2, 8},    // 高峰
    };
    private static final double[][] PRO = {
            {0.15, 4.5, 13.5},
            {0.30, 9.0, 27.0},
    };

    private Price() {
    }

    /** 是否为高峰时段（按北京时间判断）。 */
    public static boolean peak(long millis) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"), Locale.US);
        c.setTimeInMillis(millis);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false;
        int minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return (minutes >= 9 * 60 && minutes < 12 * 60)
                || (minutes >= 14 * 60 && minutes < 18 * 60);
    }

    private static double[] rates(String model, long millis) {
        String m = model == null ? "" : model.toLowerCase(Locale.US);
        double[][] table = m.contains("pro") ? PRO : FLASH;
        return table[peak(millis) ? 1 : 0];
    }

    /**
     * 估算本轮费用（元）。
     *
     * @param usage 本轮用量；缓存命中与未命中分别计价
     * @param model 模型名（含 pro 走 v4-pro 价，其余按 flash 价）
     * @param millis 请求发生时间，用于判断高峰/空闲
     */
    public static double yuan(Usage usage, String model, long millis) {
        if (usage == null) return 0;
        double[] r = rates(model, millis);
        int hit = Math.max(0, usage.cacheHit);
        int miss = Math.max(0, usage.cacheMiss);
        int out = Math.max(0, usage.outputTokens);
        return (hit * r[0] + miss * r[1] + out * r[2]) / 1_000_000.0;
    }

    /** 金额格式化：≥1 元保留两位，其余保留四位（便于看清小额）。 */
    public static String format(double yuan) {
        if (yuan <= 0) return "";
        if (yuan >= 1) return String.format(Locale.US, "¥%.2f", yuan);
        if (yuan >= 0.01) return String.format(Locale.US, "¥%.4f", yuan);
        return String.format(Locale.US, "¥%.5f", yuan);
    }
}
