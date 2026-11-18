package com.serein.chat.ui;

import android.content.Context;
import android.content.res.Configuration;

import com.serein.chat.data.Prefs;

/**
 * 调色板，取自 chat-demo 的 theme.css（--dsw-* 设计变量），亮色与深色两套。
 * 界面全部代码构建，所以主题切换只需换一套 Palette 再重建视图。
 */
public final class Theme {

    public final boolean dark;

    public final int bgBase;
    public final int bgLayer2;
    public final int bgLayer3;
    public final int bubble;
    public final int inputMajor;

    public final int labelPrimary;
    public final int labelSecondary;
    public final int labelTertiary;
    public final int labelCaption;
    public final int labelDimmed;
    public final int labelInverted;

    public int brand;
    public int brandText;
    public int ghostActiveFill;
    public int ghostActiveBorder;

    public final int borderL2;
    public final int borderL1;
    public final int hover;
    public final int hoverAccent;

    public final int codeBlock;
    public final int codeBanner;
    public final int inlineCode;
    public final int markdownTag;
    public final int citation;

    public final int sidebar;
    public final int sidebarActive;
    public final int selector;

    public final int error;
    public final int toast;
    public final int success;

    private Theme(boolean dark) {
        this.dark = dark;
        if (!dark) {
            bgBase = 0xFFFFFFFF;
            bgLayer2 = 0xFFFFFFFF;
            bgLayer3 = 0xFFFFFFFF;
            bubble = 0xFFEDF3FE;
            inputMajor = 0xFFFFFFFF;

            labelPrimary = 0xFF0F1115;
            labelSecondary = 0xFF61666B;
            labelTertiary = 0xFF81858C;
            labelCaption = 0xFFADB2B8;
            labelDimmed = 0xFFE1E5EE;
            labelInverted = 0xFFFFFFFF;

            brand = 0xFF3964FE;
            brandText = 0xFF3964FE;
            ghostActiveFill = 0xFFEDF3FE;
            ghostActiveBorder = 0xFFB7C8FE;

            borderL2 = 0x1A000000;
            borderL1 = 0x0A000000;
            hover = 0x0F263148;
            hoverAccent = 0x24263148;

            codeBlock = 0xFFF9FAFB;
            codeBanner = 0xFFF4F6F9;
            inlineCode = 0xFFEBEEF2;
            markdownTag = 0xFFF1F3F5;
            citation = 0xFFEBEEF2;

            sidebar = 0xFFF9FAFB;
            sidebarActive = 0xFFE4EDFD;
            selector = 0xFFF1F3F5;

            error = 0xFFEC1313;
            toast = 0xFF353438;
            success = 0xFF22C55E;
        } else {
            bgBase = 0xFF151517;
            bgLayer2 = 0xFF2C2C2E;
            bgLayer3 = 0xFF353438;
            bubble = 0xFF2C2C2E;
            inputMajor = 0xFF2C2C2E;

            labelPrimary = 0xFFF9FAFB;
            labelSecondary = 0xFFCFD3D6;
            labelTertiary = 0xFFADB2B8;
            labelCaption = 0xFF81858C;
            labelDimmed = 0xFF43454A;
            labelInverted = 0xFF1B1B1C;

            brand = 0xFF5686FE;
            brandText = 0xFF679EFE;
            ghostActiveFill = 0xFF282F42;
            ghostActiveBorder = 0xFF4868B2;

            borderL2 = 0x1FFFFFFF;
            borderL1 = 0x0FFFFFFF;
            hover = 0x14FFFFFF;
            hoverAccent = 0x3DFFFFFF;

            codeBlock = 0xFF1B1B1C;
            codeBanner = 0xFF2C2C2E;
            inlineCode = 0xFF2C2C2E;
            markdownTag = 0xFF2C2C2E;
            citation = 0xFF2C2C2E;

            sidebar = 0xFF1B1B1C;
            sidebarActive = 0xFF2C2C2E;
            selector = 0xFF353438;

            error = 0xFFF25A5A;
            toast = 0xFF43454A;
            success = 0xFF4ED17E;
        }
    }

    /** 自定义强调色（0 表示用默认品牌色）。 */
    public static int accentOverride;

    /** 按自定义强调色重算品牌相关颜色。 */
    public void applyAccent(int accent) {
        if (accent == 0) return;
        brand = accent;
        brandText = accent;
        ghostActiveFill = Ui.alpha(accent, dark ? 0.22f : 0.10f);
        ghostActiveBorder = Ui.alpha(accent, dark ? 0.55f : 0.38f);
    }

    private static Theme light;
    private static Theme darkPalette;

    public static Theme of(boolean dark) {
        if (dark) {
            if (darkPalette == null) darkPalette = new Theme(true);
            return darkPalette;
        }
        if (light == null) light = new Theme(false);
        return light;
    }

    /** 由偏好 + 系统设置解析是否深色。 */
    public static boolean resolveDark(Context c, int preference) {
        if (preference == Prefs.THEME_LIGHT) return false;
        if (preference == Prefs.THEME_DARK) return true;
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }
}
