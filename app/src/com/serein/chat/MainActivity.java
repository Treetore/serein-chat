package com.serein.chat;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.serein.chat.api.Api;
import com.serein.chat.api.ChatRequest;
import com.serein.chat.api.Turn;
import com.serein.chat.api.ApiException;
import com.serein.chat.data.Db;
import com.serein.chat.data.Models;
import com.serein.chat.data.Prefs;
import com.serein.chat.data.Provider;
import com.serein.chat.data.Usage;
import com.serein.chat.ui.Drawer;
import com.serein.chat.ui.InputBar;
import com.serein.chat.ui.MessageRow;
import com.serein.chat.ui.Sheets;
import com.serein.chat.ui.Theme;
import com.serein.chat.ui.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主界面：欢迎页 / 会话页、侧边栏、输入区与流式回复的全部编排。
 * 接口层见 {@link Api}，本地存储见 {@link Db}，界面构件见 ui 包。
 */
public class MainActivity extends Activity implements
        InputBar.Host, MessageRow.Host, Drawer.Host, Sheets.SettingsHost {

    private static final int REQ_VOICE = 1001;
    private static final int REQ_FILES = 1002;
    public static final int REQ_BG = 1003;
    private static final int REQ_EXPORT = 1004;
    private static final int REQ_MIC = 1006;
    private static final int REQ_IMPORT = 1005;
    private static final int MAX_FILES = 4;

    private Prefs prefs;
    private Db db;
    private Theme theme;

    private FrameLayout root;
    private FrameLayout bodyFrame;
    private ScrollView listScroll;
    private LinearLayout list;
    private LinearLayout welcome;
    private TextView headerTitle;
    private TextView balancePill;
    private View headerNewChat;
    private TextView caveat;
    private InputBar inputBar;
    private View scrim;
    private Drawer drawer;
    private FrameLayout modalHost;
    private View headerView;
    private int insetTop, insetBottom, insetLeft, insetRight;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private final List<Models.Message> messages = new ArrayList<>();
    private String sessionId;
    private String sessionTitle = "";
    private final List<Models.FileRef> pendingFiles = new ArrayList<>();

    private Models.Message streamingTarget;
    private Models.Message editingMessage;
    private Provider provider;
    private String balanceText;
    private long streamStartAt;
    private long firstTokenAt;
    private boolean balanceInFlight;
    /** 用户是否停在底部：在底部时流式输出自动跟随 */
    private boolean followOutput = true;
    /** 手指按在列表上时不做自动跟随，避免和用户滑动打架 */
    private boolean userTouching;
    /** 余额自动刷新：30s 一轮，界面不可见时停掉。 */
    private final Runnable balanceTick = new Runnable() {
        @Override
        public void run() {
            refreshBalance();
            main.postDelayed(this, 30000L);
        }
    };
    private AtomicBoolean cancelFlag;
    private boolean streamDirty;
    private boolean streamPosted;

    private TextToSpeech tts;
    private android.media.MediaPlayer player;
    private final com.serein.chat.data.WavRecorder recorder = new com.serein.chat.data.WavRecorder();
    private boolean ttsReady;

    private boolean drawerOpen;

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(applyLocale(base, new Prefs(base).locale()));
    }

    /** 按设置里的语言重建配置上下文，英文/中文分别取对应资源。 */
    private static Context applyLocale(Context base, String lang) {
        java.util.Locale locale = "en".equals(lang)
                ? java.util.Locale.ENGLISH : java.util.Locale.SIMPLIFIED_CHINESE;
        java.util.Locale.setDefault(locale);
        android.content.res.Configuration cfg =
                new android.content.res.Configuration(base.getResources().getConfiguration());
        cfg.setLocale(locale);
        return base.createConfigurationContext(cfg);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.init(this);
        prefs = new Prefs(this);
        db = new Db(this);
        theme = Theme.of(Theme.resolveDark(this, prefs.theme()));
        theme.applyAccent(prefs.accent());
        setTheme(theme.dark ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(savedInstanceState);

        provider = prefs.activeProvider();
        buildUi();

        Window w = getWindow();
        w.setStatusBarColor(theme.bgBase);
        w.setNavigationBarColor(theme.bgBase);
        Ui.setLightStatusBar(w, !theme.dark);
        refreshDrawer();
        updateWelcome();

        tts = new TextToSpeech(this, status -> ttsReady = status == TextToSpeech.SUCCESS);
        tts.setLanguage(Locale.CHINA);

        Provider active = prefs.activeProvider();
        if (active.apiKey == null || active.apiKey.trim().isEmpty()) {
            main.postDelayed(this::openSettings, 350);
        }

        applyDebugIntent(getIntent());
        handleSendIntent(getIntent());
    }

    /**
     * 调试入口：am start -e proto responses -e autosend 1
     * 仅用于本地联调（切换当前服务商协议 / 自动发送一条消息），不影响正常使用。
     */
    private void applyDebugIntent(Intent it) {
        if (it == null) return;
        String proto = it.getStringExtra("proto");
        if (proto != null && !proto.isEmpty()) {
            Provider p = prefs.activeProvider();
            if (p != null) {
                p.protocol = proto;
                prefs.updateProvider(p);
                provider = prefs.activeProvider();
                buildUi();
            }
        }
        if ("1".equals(it.getStringExtra("newchat"))) {
            main.postDelayed(this::newChat, 200);
        }
        if ("1".equals(it.getStringExtra("autosend"))) {
            String text = it.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && !text.trim().isEmpty()) {
                main.postDelayed(() -> onSend(text.trim()), 700);
            }
        }
    }

    /** 系统分享进来的文本填入输入框，避免用户再粘贴一次。 */
    private void handleSendIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text == null || text.toString().trim().isEmpty()) return;
        final String value = text.toString().trim();
        if (inputBar != null) {
            inputBar.appendText(value);
            intent.setAction(null);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSendIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(balanceTick);
        main.postDelayed(balanceTick, 30000L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(balanceTick);
    }

    @Override
    protected void onDestroy() {
        if (cancelFlag != null) cancelFlag.set(true);
        io.shutdownNow();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (prefs != null && prefs.theme() == Prefs.THEME_SYSTEM) {
            boolean dark = Theme.resolveDark(this, Prefs.THEME_SYSTEM);
            if (dark != theme.dark) {
                main.post(this::rebuildForTheme);
            }
        }
    }

    /** 主题切换：原地重建视图，避免 Activity 重启动把会话状态丢掉。 */
    private void rebuildForTheme() {
        String draft = inputBar == null ? "" : inputBar.text();
        theme = Theme.of(Theme.resolveDark(this, prefs.theme()));
        theme.applyAccent(prefs.accent());
        setTheme(theme.dark ? R.style.AppTheme_Dark : R.style.AppTheme);
        drawerOpen = false;
        provider = prefs.activeProvider();
        buildUi();
        applyBarStyle();
        refreshBalance();
        inputBar.setToggles(prefs.thinking(), prefs.search());
        if (draft != null && !draft.isEmpty()) inputBar.editText().setText(draft);
        renderMessages();
        refreshDrawer();
        updateWelcome();
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        if (modalHost.getChildCount() > 0) {
            modalHost.removeAllViews();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ 界面搭建

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(theme.bgBase);
        applyBackgroundImage();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content.addView(buildHeader());

        bodyFrame = new FrameLayout(this);
        content.addView(bodyFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(16), Ui.dp(6), Ui.dp(16), Ui.dp(12));
        listScroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 内容高度变化后立即贴底：比 post 更可靠，布局一结束就执行
        list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or0, ob) -> {
            if (streamingTarget != null && followOutput && !userTouching) {
                listScroll.scrollTo(0, Math.max(0, b - listScroll.getHeight()));
            }
        });
        // 只有用户手指拖动才算「放弃跟随」，程序滚动不改变跟随状态
        listScroll.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    userTouching = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    userTouching = false;
                    followOutput = isNearBottom();
                    break;
                default:
                    break;
            }
            return false;
        });
        bodyFrame.addView(listScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        welcome = new LinearLayout(this);
        welcome.setOrientation(LinearLayout.VERTICAL);
        welcome.setGravity(Gravity.CENTER);
        TextView greeting = Ui.text(this, getString(R.string.welcome_message), 20, theme.labelPrimary);
        greeting.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        greeting.setGravity(Gravity.CENTER);
        welcome.addView(greeting, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        welcome.setPadding(Ui.dp(32), 0, Ui.dp(32), Ui.dp(24));
        bodyFrame.addView(welcome, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        caveat = Ui.text(this, getString(R.string.content_caveat), 11, theme.labelTertiary);
        caveat.setGravity(Gravity.CENTER);
        caveat.setPadding(0, Ui.dp(2), 0, Ui.dp(6));
        content.addView(caveat, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        inputBar = new InputBar(this, theme, this);
        inputBar.setToggles(prefs.thinking(), prefs.search());
        applyThinkingStrength();
        content.addView(inputBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 抽屉遮罩
        scrim = new View(this);
        scrim.setBackgroundColor(0x55000000);
        scrim.setAlpha(0f);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeDrawer());
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawer = new Drawer(this, theme, this);
        drawer.setTranslationX(-drawer.panelWidth());
        drawer.setVisibility(View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(
                drawer.panelWidth(), ViewGroup.LayoutParams.MATCH_PARENT));

        modalHost = new FrameLayout(this);
        root.addView(modalHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        setupEdgeToEdge();
    }

    /** 系统栏透明 + 图标明暗跟随主题（API29+ 关闭导航条强制对比底色）。 */
    private void applyBarStyle() {
        Window w = getWindow();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            w.setNavigationBarContrastEnforced(false);
        }
        Ui.setLightStatusBar(w, !theme.dark);
    }

    /** 自定义背景图：铺满 + 按设置暗化，保证前景文字可读。 */
    private void applyBackgroundImage() {
        String path = prefs.backgroundImage();
        if (path == null || path.isEmpty()) return;
        File f = new File(path);
        if (!f.exists()) return;
        android.widget.ImageView bg = new android.widget.ImageView(this);
        bg.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, o);
            int sample = 1;
            int max = Math.max(o.outWidth, o.outHeight);
            while (max / sample > 1600) sample *= 2;
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            bg.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path, opts));
        } catch (Throwable ignored) {
        }
        root.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View dim = new View(this);
        int alpha = Math.round(prefs.backgroundDim() / 100f * 255);
        dim.setBackgroundColor(android.graphics.Color.argb(alpha, 0, 0, 0));
        root.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 边到边绘制 + insets 注入：状态栏、刘海/挖孔、手势导航、输入法统一在这里让位。 */
    private void setupEdgeToEdge() {
        Window w = getWindow();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            readInsets(insets);
            applyInsets();
            return insets;
        });
        root.requestApplyInsets();
    }

    private void readInsets(android.view.WindowInsets insets) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
            insetTop = bars.top;
            insetLeft = bars.left;
            insetRight = bars.right;
            insetBottom = Math.max(bars.bottom, ime.bottom);
        } else {
            insetTop = insets.getSystemWindowInsetTop();
            insetBottom = insets.getSystemWindowInsetBottom();
            insetLeft = insets.getSystemWindowInsetLeft();
            insetRight = insets.getSystemWindowInsetRight();
            android.view.DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                insetTop = Math.max(insetTop, cutout.getSafeInsetTop());
                insetBottom = Math.max(insetBottom, cutout.getSafeInsetBottom());
                insetLeft = Math.max(insetLeft, cutout.getSafeInsetLeft());
                insetRight = Math.max(insetRight, cutout.getSafeInsetRight());
            }
        }
        Ui.insetTop = insetTop;
        Ui.insetBottom = insetBottom;
    }

    private void applyInsets() {
        if (headerView != null) {
            // 顶栏高度随状态栏/刘海增高，避免内边距把标题挤到裁剪区
            ViewGroup.LayoutParams lp = headerView.getLayoutParams();
            if (lp != null && lp.height != Ui.dp(52) + insetTop) {
                lp.height = Ui.dp(52) + insetTop;
                headerView.setLayoutParams(lp);
            }
            headerView.setPadding(Ui.dp(6) + insetLeft, insetTop + Ui.dp(4),
                    Ui.dp(6) + insetRight, Ui.dp(4));
        }
        if (inputBar != null) {
            inputBar.setPadding(insetLeft, 0, insetRight, insetBottom);
        }
        if (drawer != null) {
            drawer.setPadding(insetLeft, insetTop, insetRight, insetBottom);
        }
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(6), Ui.dp(4), Ui.dp(6), Ui.dp(4));
        header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(52)));

        ImageView menu = Ui.iconButton(this, R.drawable.ic_menu, theme.labelPrimary, theme);
        menu.setContentDescription(getString(R.string.expand_sider));
        menu.setOnClickListener(v -> openDrawer());
        header.addView(menu);

        balancePill = Ui.text(this, "", 12, theme.labelTertiary);
        balancePill.setPadding(Ui.dp(8), Ui.dp(3), Ui.dp(8), Ui.dp(3));
        balancePill.setBackground(Ui.roundStroke(theme.bgLayer2, theme.borderL1, 1, 100));
        balancePill.setVisibility(View.GONE);
        balancePill.setOnClickListener(v -> openBalancePanel());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = Ui.dp(6);
        header.addView(balancePill, blp);

        headerTitle = Ui.text(this, "", 16, theme.labelPrimary);
        headerTitle.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        headerTitle.setGravity(Gravity.CENTER);
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        headerNewChat = Ui.iconButton(this, R.drawable.ic_new_chat, theme.labelPrimary, theme);
        headerNewChat.setContentDescription(getString(R.string.new_chat));
        headerNewChat.setOnClickListener(v -> newChat());
        headerNewChat.setVisibility(View.GONE);
        header.addView(headerNewChat);

        header.setOnClickListener(v -> {
            if (messages.isEmpty()) openDrawer();
        });
        headerView = header;
        return header;
    }

    // ------------------------------------------------------------------ 列表渲染

    private void updateWelcome() {
        boolean empty = messages.isEmpty();
        welcome.setVisibility(empty ? View.VISIBLE : View.GONE);
        listScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        headerTitle.setText(sessionTitle == null || sessionTitle.isEmpty()
                ? getString(R.string.untitled_chat) : sessionTitle);
        headerNewChat.setVisibility(empty ? View.GONE : View.VISIBLE);
        caveat.setVisibility(empty ? View.VISIBLE : View.GONE);
        headerTitle.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
    }

    private void renderMessages() {
        list.removeAllViews();
        for (int i = 0; i < messages.size(); i++) {
            Models.Message m = messages.get(i);
            MessageRow row = new MessageRow(this, theme, m, this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(i == 0 ? 10 : 18);
            lp.bottomMargin = Ui.dp(m.role == Models.ROLE_USER ? 2 : 6);
            list.addView(row, lp);
        }
        updateWelcome();
        scrollToBottom();
        com.serein.chat.ui.Anim.enter(list);
    }

    private void appendRow(Models.Message m) {
        MessageRow row = new MessageRow(this, theme, m, this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(messages.size() <= 1 ? 10 : 18);
        lp.bottomMargin = Ui.dp(m.role == Models.ROLE_USER ? 2 : 6);
        list.addView(row, lp);
        com.serein.chat.ui.Anim.enter(row);
        updateWelcome();
    }

    private void scrollToBottom() {
        followOutput = true;
        listScroll.post(() -> listScroll.scrollTo(0,
                Math.max(0, list.getHeight() - listScroll.getHeight())));
    }

    /** 流式渲染节流：最多约 12fps 重绘。 */
    private void scheduleStreamUpdate() {
        streamDirty = true;
        if (streamPosted) return;
        streamPosted = true;
        main.postDelayed(() -> {
            streamPosted = false;
            if (!streamDirty) return;
            streamDirty = false;
            if (streamingTarget == null) return;
            MessageRow row = findRow(streamingTarget);
            if (row != null) row.setMessage(streamingTarget);
            if (followOutput && !userTouching) {
                listScroll.scrollTo(0, Math.max(0, list.getHeight() - listScroll.getHeight()));
            }
        }, com.serein.chat.ui.Anim.streamIntervalMs());
    }

    private boolean isNearBottom() {
        View child = listScroll.getChildAt(0);
        if (child == null) return true;
        return listScroll.getScrollY() + listScroll.getHeight() >= child.getHeight() - Ui.dp(120);
    }

    private MessageRow findRow(Models.Message target) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v instanceof MessageRow) {
                MessageRow r = (MessageRow) v;
                if (r.tag() == target) return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 会话管理

    private void newChat() {
        if (streamingTarget != null) stopStreaming();
        sessionId = null;
        sessionTitle = "";
        editingMessage = null;
        messages.clear();
        pendingFiles.clear();
        inputBar.setFiles(pendingFiles);
        inputBar.clearText();
        String draft = prefs.draft(null);
        if (!draft.isEmpty()) inputBar.editText().setText(draft);
        list.removeAllViews();
        updateWelcome();
        headerTitle.setText("");
        refreshDrawer();
        closeDrawer();
        inputBar.focusInput();
    }

    private void openSession(Models.Session s) {
        if (streamingTarget != null) stopStreaming();
        sessionId = s.id;
        sessionTitle = s.title;
        editingMessage = null;
        messages.clear();
        messages.addAll(db.listMessages(s.id));
        pendingFiles.clear();
        inputBar.setFiles(pendingFiles);
        inputBar.clearText();
        String draft = prefs.draft(s.id);
        if (!draft.isEmpty()) inputBar.editText().setText(draft);
        renderMessages();
        refreshDrawer();
        closeDrawer();
    }

    private void refreshDrawer() {
        drawer.setSessions(db.listSessions(), sessionId);
    }

    private void ensureSession() {
        if (sessionId != null) return;
        Models.Session s = new Models.Session();
        s.id = UUID.randomUUID().toString();
        s.title = "";
        s.createdAt = System.currentTimeMillis();
        s.updatedAt = s.createdAt;
        db.upsertSession(s);
        sessionId = s.id;
        sessionTitle = "";
    }

    private void touchSession() {
        if (sessionId == null) return;
        for (Models.Session s : db.listSessions()) {
            if (s.id.equals(sessionId)) {
                s.updatedAt = System.currentTimeMillis();
                if (sessionTitle != null && !sessionTitle.isEmpty()) s.title = sessionTitle;
                db.upsertSession(s);
                break;
            }
        }
        refreshDrawer();
    }

    // ------------------------------------------------------------------ 发送

    @Override
    public void onSend(String text) {
        if (provider == null) provider = prefs.activeProvider();
        if (provider.apiKey == null || provider.apiKey.trim().isEmpty()) {
            openSettings();
            Ui.toast(this, getString(R.string.api_key_start));
            return;
        }
        for (Models.FileRef f : pendingFiles) {
            if ("uploading".equals(f.status)) {
                Ui.toast(this, getString(R.string.attach_uploading));
                return;
            }
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() && pendingFiles.isEmpty()) return;

        // 编辑提问后发送：该条及其之后的回答全部作废，从这条重新开始
        if (editingMessage != null) {
            truncateFrom(editingMessage);
            editingMessage = null;
        }

        ensureSession();

        Models.Message user = new Models.Message();
        user.id = UUID.randomUUID().toString();
        user.role = Models.ROLE_USER;
        user.createdAt = System.currentTimeMillis();
        user.text = trimmed;
        user.files.addAll(pendingFiles);
        pendingFiles.clear();
        inputBar.setFiles(pendingFiles);
        inputBar.clearText();
        prefs.clearDraft(sessionId);

        Models.Message reply = new Models.Message();
        reply.id = UUID.randomUUID().toString();
        reply.role = Models.ROLE_ASSISTANT;
        reply.createdAt = System.currentTimeMillis();
        reply.thinking = prefs.thinking();
        reply.search = prefs.search();
        reply.model = currentNodeModel();
        reply.status = Models.S_PENDING;

        messages.add(user);
        messages.add(reply);
        db.saveMessage(sessionId, user);
        db.saveMessage(sessionId, reply);

        boolean isFirstRound = messages.size() <= 2;
        if (isFirstRound && (sessionTitle == null || sessionTitle.isEmpty())) {
            String shortTitle = trimmed.length() > 24 ? trimmed.substring(0, 24) : trimmed;
            sessionTitle = shortTitle.isEmpty() ? getString(R.string.untitled_chat) : shortTitle;
            db.renameSession(sessionId, sessionTitle);
        }

        followOutput = true;
        appendRow(user);
        appendRow(reply);
        updateWelcome();
        scrollToBottom();
        inputBar.setStreaming(true);
        startStream(reply, user, isFirstRound);
    }

    /** 重新生成：把当前回复存为旧版本，新增一个版本并重新请求。 */
    @Override
    public void onRegenerate(Models.Message m) {
        if (streamingTarget != null) return;
        int idx = messages.indexOf(m);
        if (idx <= 0) return;
        Models.Message question = null;
        for (int i = idx - 1; i >= 0; i--) {
            if (messages.get(i).role == Models.ROLE_USER) {
                question = messages.get(i);
                break;
            }
        }
        if (question == null) return;

        if (m.alternatives.isEmpty()) {
            m.alternatives.add(snapshot(m));
        } else {
            m.alternatives.set(m.activeVersion, snapshot(m));
        }
        Models.Message fresh = new Models.Message();
        fresh.id = m.id;
        fresh.role = Models.ROLE_ASSISTANT;
        fresh.createdAt = System.currentTimeMillis();
        fresh.thinking = prefs.thinking();
        fresh.search = prefs.search();
        fresh.model = currentNodeModel();
        fresh.status = Models.S_PENDING;
        m.alternatives.add(fresh);
        m.activeVersion = m.alternatives.size() - 1;

        m.blocks.clear();
        m.status = Models.S_PENDING;
        m.error = "";
        m.stopReason = "";
        m.thinking = fresh.thinking;
        m.search = fresh.search;
        m.model = fresh.model;

        MessageRow row = findRow(m);
        if (row != null) row.setMessage(m);
        inputBar.setStreaming(true);
        startStream(m, question, false);
    }

    private Models.Message snapshot(Models.Message m) {
        Models.Message s = new Models.Message();
        s.id = m.id;
        s.role = m.role;
        s.createdAt = m.createdAt;
        s.status = m.status;
        s.stopReason = m.stopReason;
        s.error = m.error;
        s.thinking = m.thinking;
        s.search = m.search;
        s.model = m.model;
        s.text = m.text;
        s.files.addAll(m.files);
        for (Models.Block b : m.blocks) {
            Models.Block nb = Models.Block.from(b.toJson());
            s.blocks.add(nb);
        }
        return s;
    }

    private void switchVersion(Models.Message m, int target) {
        if (target < 0 || target >= m.alternatives.size()) return;
        if (target == m.activeVersion) return;
        m.alternatives.set(m.activeVersion, snapshot(m));
        Models.Message t = m.alternatives.get(target);
        m.blocks.clear();
        for (Models.Block b : t.blocks) m.blocks.add(Models.Block.from(b.toJson()));
        m.status = t.status;
        m.stopReason = t.stopReason;
        m.error = t.error;
        m.activeVersion = target;
        MessageRow row = findRow(m);
        if (row != null) row.setMessage(m);
        if (sessionId != null) db.saveMessage(sessionId, m);
    }

    // ------------------------------------------------------------------ 流式请求

    private void startStream(Models.Message reply, Models.Message question, boolean withTitle) {
        startStream(reply, question, withTitle, null);
    }

    /** prefix 非空时把已有正文作为 assistant 前缀，让服务端接着写（继续生成）。 */
    private void startStream(Models.Message reply, Models.Message question, boolean withTitle, String prefix) {
        if (cancelFlag != null) cancelFlag.set(true);
        cancelFlag = new AtomicBoolean(false);
        streamingTarget = reply;
        firstTokenAt = 0;
        reply.status = Models.S_STREAMING;

        List<Models.Message> history = new ArrayList<>();
        for (Models.Message m : messages) {
            if (m == reply) break;
            if (m != question) history.add(m);
        }

        if (provider == null) provider = prefs.activeProvider();
        ChatRequest request = buildRequest(question, history, withTitle, prefix);
        if (provider.apiKey == null || provider.apiKey.trim().isEmpty()) {
            finishStream(reply, Models.S_ERROR, getString(R.string.reply_invalid_key));
            return;
        }
        streamStartAt = System.currentTimeMillis();

        AtomicBoolean cancelled = cancelFlag;
        final java.util.Map<Integer, Models.Block> indexMap = new java.util.HashMap<>();
        io.execute(() -> {
            Api.chat(provider, request, new Api.Listener() {
                @Override
                public void onMessageStart(String messageId, String model) {
                }

                @Override
                public void onBlockStart(int index, Models.Block block) {
                    if (cancelled.get()) return;
                    if (block.type == Models.B_THINKING) block.startedAt = System.currentTimeMillis();
                    main.post(() -> {
                        indexMap.put(index, block);
                        reply.blocks.add(block);
                        scheduleStreamUpdate();
                    });
                }

                @Override
                public void onTextDelta(int index, String text) {
                    if (cancelled.get() || text == null) return;
                    main.post(() -> {
                        Models.Block b = indexMap.get(index);
                        if (b != null && b.type == Models.B_TEXT) {
                            if (firstTokenAt == 0) firstTokenAt = System.currentTimeMillis();
                            b.appendText(text);
                        }
                        scheduleStreamUpdate();
                    });
                }

                @Override
                public void onThinkingDelta(int index, String text) {
                    if (cancelled.get() || text == null) return;
                    main.post(() -> {
                        Models.Block b = indexMap.get(index);
                        if (b != null && b.type == Models.B_THINKING) b.appendThinking(text);
                        scheduleStreamUpdate();
                    });
                }

                @Override
                public void onSignatureDelta(int index, String signature) {
                    main.post(() -> {
                        Models.Block b = indexMap.get(index);
                        if (b != null) b.appendSignature(signature);
                    });
                }

                @Override
                public void onCitation(int index, Models.Citation citation) {
                    main.post(() -> {
                        Models.Block b = indexMap.get(index);
                        if (b != null) {
                            b.citations.add(citation);
                            if (citation != null) b.addCitationRaw(citation.url, citation.title, citation.text);
                        }
                    });
                }

                @Override
                public void onBlockStop(int index) {
                    main.post(() -> {
                        Models.Block b = indexMap.get(index);
                        if (b != null && b.type == Models.B_THINKING && b.endedAt == 0) {
                            b.endedAt = System.currentTimeMillis();
                        }
                        scheduleStreamUpdate();
                    });
                }

                @Override
                public void onUsage(com.serein.chat.data.Usage usage) {
                    main.post(() -> {
                        if (usage == null) return;
                        long elapsed = System.currentTimeMillis() - streamStartAt;
                        usage.elapsedMs = elapsed;
                        reply.usage = usage;
                        MessageRow row = findRow(reply);
                        if (row != null) row.setMessage(reply);
                    });
                }

                @Override
                public void onMessageEnd(String stopReason, String title, String failureNote) {
                    main.post(() -> {
                        reply.stopReason = stopReason == null ? "" : stopReason;
                        if (title != null && !title.trim().isEmpty()) {
                            sessionTitle = title.trim();
                            if (sessionId != null) db.renameSession(sessionId, sessionTitle);
                            headerTitle.setText(sessionTitle);
                            refreshDrawer();
                        }
                        if ("refusal".equals(failureNote) || "refusal".equals(stopReason)) {
                            // 服务端拒答：用统一文案替换已展示内容，该轮不进入后续上下文
                            reply.blocks.clear();
                            Models.Block rb = new Models.Block();
                            rb.type = Models.B_TEXT;
                            rb.text = getString(R.string.message_refused);
                            reply.blocks.add(rb);
                            reply.refused = true;
                            finishStream(reply, Models.S_DONE, null);
                        } else {
                            finishStream(reply, cancelled.get() ? Models.S_STOPPED : Models.S_DONE, null);
                        }
                    });
                }

                @Override
                public void onError(ApiException e) {
                    main.post(() -> finishStream(reply, Models.S_ERROR, describeError(e)));
                }
            }, cancelled);
        });
    }


    /** latest_reminder：<date>,<weekday>,<location>,<platform>,<language> */
    private String buildLatestReminder() {
        try {
            boolean zh = getResources().getConfiguration().getLocales().get(0)
                    .getLanguage().startsWith("zh");
            java.util.Locale loc = zh ? java.util.Locale.CHINA : java.util.Locale.US;
            java.util.Date now = new java.util.Date();
            String date = new java.text.SimpleDateFormat("yyyy-MM-dd", loc).format(now);
            String weekday = new java.text.SimpleDateFormat("EEEE", loc).format(now);
            String language = zh ? "中文" : "English";
            String location = prefs.location();
            return date + "," + weekday + "," + (location == null ? "" : location) + ",App," + language;
        } catch (Exception e) {
            return "";
        }
    }

    /** 组合可回传的协议原生负载：Anthropic 用块数组，OpenAI 用消息对象。 */
    private String buildRawPayload(Models.Message reply) {
        try {
            Provider p = provider == null ? prefs.activeProvider() : provider;
            if (p != null && Provider.PROTO_ANTHROPIC.equals(p.protocol)) {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (Models.Block b : reply.blocks) {
                    String rw = b.rawJson();
                    if (rw == null || rw.isEmpty()) continue;
                    org.json.JSONObject o = new org.json.JSONObject(rw);
                    // 历史遗留或降级路径可能缺 type：这类块不参与回传，避免服务端 400
                    if (o.optString("type").isEmpty()) continue;
                    arr.put(o);
                }
                return arr.length() == 0 ? "" : arr.toString();
            }
            org.json.JSONObject msg = new org.json.JSONObject();
            msg.put("role", "assistant");
            msg.put("content", reply.plainText());
            String thinking = "";
            for (Models.Block b : reply.blocks) {
                if (b.type == Models.B_THINKING) thinking = b.text;
            }
            if (!thinking.isEmpty()) msg.put("reasoning_content", thinking);
            return msg.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void finishStream(Models.Message reply, String status, String error) {
        streamingTarget = null;
        // 结束后动作栏与用量条是后加的，需要再贴一次底
        if (followOutput) {
            listScroll.post(() -> {
                listScroll.scrollTo(0, Math.max(0, list.getHeight() - listScroll.getHeight()));
                listScroll.post(() -> listScroll.scrollTo(0,
                        Math.max(0, list.getHeight() - listScroll.getHeight())));
            });
        }
        reply.status = status;
        if (error != null) reply.error = error;
        for (Models.Block b : reply.blocks) {
            if (b.type == Models.B_THINKING && b.endedAt == 0 && b.startedAt > 0) {
                b.endedAt = System.currentTimeMillis();
            }
        }
        // 固化协议原生内容：下一轮原样回传（thinking 签名 / 工具块 / 引用）
        reply.rawJson = buildRawPayload(reply);
        // 冻结本轮答复正文：后续轮次原样复用，服务端前缀缓存更容易命中
        if (Models.S_DONE.equals(status) && reply.hasVisibleText()) {
            reply.sentText = reply.plainText();
        }
        if (reply.usage != null && firstTokenAt > 0 && streamStartAt > 0) {
            reply.usage.firstTokenMs = Math.max(0, firstTokenAt - streamStartAt);
        }
        if (reply.usage != null && Models.S_DONE.equals(status)) {
            db.logUsage(reply.model == null ? "" : reply.model, reply.usage);
        }
        if (reply.usage != null && reply.usage.elapsedMs <= 0) {
            reply.usage.elapsedMs = Math.max(1, System.currentTimeMillis() - streamStartAt);
        }
        if (sessionId != null) db.saveMessage(sessionId, reply);
        touchSession();
        MessageRow row = findRow(reply);
        if (row != null) row.setMessage(reply);
        inputBar.setStreaming(false);
        refreshBalance();
    }

    private void stopStreaming() {
        if (cancelFlag != null) cancelFlag.set(true);
        if (streamingTarget != null) {
            finishStream(streamingTarget, Models.S_STOPPED, null);
        }
        inputBar.setStreaming(false);
    }

    @Override
    public void onStopGenerating() {
        stopStreaming();
    }

    private String describeError(ApiException e) {
        if (e.isAuthError()) return getString(R.string.reply_invalid_key);
        if (e.isInsufficientBalance()) return getString(R.string.reply_no_balance);
        if (e.isRateLimited()) return getString(R.string.reply_rate_limited);
        if (e.kind == ApiException.NETWORK) return getString(R.string.network_error);
        if (e.kind == ApiException.INTERRUPTED) return getString(R.string.err_interrupted);
        if (e.kind == ApiException.PROTOCOL) return getString(R.string.err_protocol);
        if (e.status >= 500) return getString(R.string.server_busy);
        String m = e.getMessage();
        return m == null || m.isEmpty() ? getString(R.string.unknown_error) : m;
    }

    // ------------------------------------------------------------------ 请求体

    /** 统一请求：历史只保留「提问 + 可用回答」成对的轮次，末轮接入本轮提问；prefix 用于继续生成。 */
    private ChatRequest buildRequest(Models.Message question, List<Models.Message> history,
                                     boolean withTitle, String prefix) {
        ChatRequest req = new ChatRequest();
        Provider.ModelInfo info = currentModelInfo();
        req.model = currentNodeModel();
        req.system = provider != null && provider.official() ? getString(R.string.app_identity) : "";
        req.thinking = prefs.thinking() && info != null && info.thinking;
        req.effort = req.thinking && provider != null && provider.official()
                ? effortFor(prefs.thinkingLevel()) : null;
        req.search = prefs.search() && provider != null && provider.official();
        // 思考或联网开启时，采样参数由服务端接管，不再下发
        boolean samplingAllowed = !req.thinking && !req.search;
        req.temperature = samplingAllowed ? prefs.temperature() : 0f;
        req.maxTokens = samplingAllowed ? prefs.maxTokens() : 0;
        req.vision = info == null || info.vision;
        req.withTitle = withTitle && provider != null && provider.official();

        for (int i = 0; i + 1 < history.size(); i++) {
            Models.Message u = history.get(i);
            Models.Message a = history.get(i + 1);
            if (u.role != Models.ROLE_USER || a.role != Models.ROLE_ASSISTANT) continue;
            if (Models.S_ERROR.equals(a.status) || a.refused) continue;
            String answer = a.contextText();
            if (answer.trim().isEmpty()) continue;
            if (u.reminder != null && !u.reminder.isEmpty()) {
                req.turns.add(new Turn(Turn.LATEST_REMINDER, u.reminder));
            }
            req.turns.add(userTurn(u));
            Turn at = new Turn(Turn.ASSISTANT, answer);
            if (a.rawJson != null && !a.rawJson.isEmpty()) at.rawJson = a.rawJson;
            req.turns.add(at);
            i++;
        }
        if (question != null) {
            question.reminder = buildLatestReminder();
            if (!question.reminder.isEmpty()) {
                req.turns.add(new Turn(Turn.LATEST_REMINDER, question.reminder));
            }
            req.turns.add(userTurn(question));
        }
        if (prefix != null && !prefix.trim().isEmpty()) {
            req.turns.add(new Turn(Turn.ASSISTANT, prefix));
        }
        return req;
    }

    private Turn userTurn(Models.Message u) {
        Turn turn = new Turn(Turn.USER, u.text == null ? "" : u.text);
        turn.files.addAll(u.files);
        return turn;
    }

    /** 当前生效的模型 id（服务商默认模型优先）。 */
    private String currentNodeModel() {
        if (provider == null) provider = prefs.activeProvider();
        if (provider.model != null && !provider.model.isEmpty()) return provider.model;
        return provider.models.isEmpty() ? "" : provider.models.get(0).id;
    }

    private Provider.ModelInfo currentModelInfo() {
        if (provider == null) return null;
        return provider.modelInfo(currentNodeModel());
    }

    /** 拉取 DeepSeek 余额并刷新顶栏（其它服务商不展示）。 */
    private void refreshBalance() {
        if (provider == null) provider = prefs.activeProvider();
        if (!provider.official() || provider.apiKey == null || provider.apiKey.trim().isEmpty()) {
            balanceText = null;
            updateBalancePill();
            return;
        }
        if (balanceInFlight) return;
        balanceInFlight = true;
        final Provider target = provider;
        io.execute(() -> {
            String text = Api.balance(target);
            main.post(() -> {
                balanceInFlight = false;
                balanceText = text;
                updateBalancePill();
            });
        });
    }

    private void openBalancePanel() {
        if (provider == null) provider = prefs.activeProvider();
        com.serein.chat.ui.SettingsPanels.balance(this, theme, modalHost, prefs, db, provider,
                this::refreshBalance);
    }

    private void updateBalancePill() {
        if (balancePill == null) return;
        if (balanceText == null || balanceText.isEmpty()) {
            balancePill.setVisibility(View.GONE);
        } else {
            balancePill.setText(balanceText);
            balancePill.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------ 消息行回调

    @Override
    public void onCopy(Models.Message m) {
        String text = m.role == Models.ROLE_USER ? m.text : m.plainText();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            Ui.copy(this, "chat", text);
            Ui.toast(this, getString(R.string.copied));
        }
    }

    @Override
    public void onSpeak(Models.Message m) {
        final String text = m.plainText();
        if (text.trim().isEmpty()) return;
        if (provider == null) provider = prefs.activeProvider();
        final Provider target = provider;
        boolean canApi = target != null && target.official()
                && target.apiKey != null && !target.apiKey.trim().isEmpty();
        if (!canApi) {
            speakLocally(text, null);
            return;
        }
        io.execute(() -> {
            File out = new File(getCacheDir(), "tts-" + System.currentTimeMillis() + ".wav");
            String err = Api.tts(target, text, prefs.ttsVoice(), out, null);
            main.post(() -> {
                if (err == null) playWav(out);
                else speakLocally(text, err);
            });
        });
    }

    /** 播放接口返回的 wav；占位实现见 playWav(File)。 */
    private void playWav(File f) {
        try {
            if (player != null) {
                player.release();
                player = null;
            }
            player = new android.media.MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(mp -> {
                try { mp.release(); } catch (Exception ignored) {}
                if (player == mp) player = null;
            });
            player.prepare();
            player.start();
            Ui.toast(this, getString(R.string.tts_play));
        } catch (Exception e) {
            speakLocally("", e.getMessage());
        }
    }

    /** 系统 TTS 兜底（三方服务商或接口失败时）。 */
    private void speakLocally(String text, String why) {
        if (tts == null || !ttsReady) {
            Ui.toast(this, getString(R.string.tts_unavailable));
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            Ui.toast(this, why == null ? getString(R.string.tts_unavailable) : why);
            return;
        }
        tts.stop();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply");
        Ui.toast(this, getString(R.string.tts_play));
    }

    @Override
    public void onOpenSources(Models.Message m) {
        // 合并「搜索来源」与「正文引用」，按 URL 去重，保持出现顺序
        List<Models.Source> all = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Models.Block b : m.blocks) {
            if (b.type == Models.B_SEARCH_RESULT) {
                for (Models.Source s : b.sources) {
                    if (s == null || s.url == null || s.url.isEmpty()) continue;
                    if (!seen.add(s.url)) continue;
                    all.add(s);
                }
            }
            for (Models.Citation c : b.citations) {
                if (c == null || c.url == null || c.url.isEmpty()) continue;
                if (!seen.add(c.url)) continue;
                Models.Source s = new Models.Source();
                s.url = c.url;
                s.title = c.title == null ? "" : c.title;
                s.snippet = c.text == null ? "" : c.text;
                s.site = hostOf(c.url);
                all.add(s);
            }
        }
        Sheets.sources(this, theme, modalHost, all);
    }

    /** 取 URL 的站点名，供引用列表展示。 */
    private static String hostOf(String url) {
        try {
            String h = android.net.Uri.parse(url).getHost();
            if (h == null) return "";
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onRetry(Models.Message m) {
        onRegenerate(m);
    }

    @Override
    public void onSwitchVersion(Models.Message m, int delta) {
        switchVersion(m, m.activeVersion + delta);
    }

    /** 删除从 target（含）到末尾的消息，本地库同步。 */
    private void truncateFrom(Models.Message target) {
        int idx = messages.indexOf(target);
        if (idx < 0) return;
        for (int i = messages.size() - 1; i >= idx; i--) {
            Models.Message m = messages.remove(i);
            if (sessionId != null) db.deleteMessage(m.id);
        }
        renderMessages();
        touchSession();
    }

    @Override
    public void onShare(Models.Message m) {
        String text = m.role == Models.ROLE_USER ? m.text : m.plainText();
        if (text == null || text.trim().isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(i, getString(R.string.share)));
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.unknown_error));
        }
    }

    @Override
    public void onDraftChanged(String text) {
        prefs.setDraft(sessionId, text);
    }

    @Override
    public void onContinue(Models.Message m) {
        if (streamingTarget != null) return;
        int idx = messages.indexOf(m);
        if (idx <= 0) return;
        Models.Message question = null;
        for (int i = idx - 1; i >= 0; i--) {
            if (messages.get(i).role == Models.ROLE_USER) {
                question = messages.get(i);
                break;
            }
        }
        if (question == null) return;
        m.status = Models.S_STREAMING;
        m.error = "";
        inputBar.setStreaming(true);
        MessageRow row = findRow(m);
        if (row != null) row.setMessage(m);
        startStream(m, question, false, m.plainText());
    }

    @Override
    public void onEditMessage(Models.Message m) {
        if (streamingTarget != null) {
            Ui.toast(this, getString(R.string.stop_generating));
            return;
        }
        editingMessage = m;
        inputBar.editText().setText(m.text == null ? "" : m.text);
        inputBar.editText().setSelection(inputBar.editText().getText().length());
        inputBar.focusInput();
    }

    // ------------------------------------------------------------------ 输入区回调

    @Override
    public void onAttach() {
        // 文档解析不依赖视觉能力，是否可用在选完文件后按类型判断
        if (pendingFiles.size() >= MAX_FILES) {
            Ui.toast(this, getString(R.string.attach_limit, MAX_FILES));
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_FILES);
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.picker_unavailable));
        }
    }

    @Override
    public void onToggleThinking(boolean on) {
        prefs.setThinking(on);
    }

    /** 只有 DeepSeek 官方服务才展示思考强度入口。 */
    private void applyThinkingStrength() {
        if (inputBar == null) return;
        boolean official = provider != null && provider.official();
        inputBar.setStrengthVisible(official);
        inputBar.setLevel(prefs.thinkingLevel());
    }

    @Override
    public void onThinkingLevelChanged(int level) {
        prefs.setThinkingLevel(level);
    }

    /** 三档思考强度：低 / 高 / 最高，对应文档的 low / high / max。 */
    private static String effortFor(int level) {
        return level == 0 ? "low" : level == 2 ? "max" : "high";
    }

    @Override
    public void onToggleSearch(boolean on) {
        prefs.setSearch(on);
    }

    @Override
    public void onVoice() {
        if (provider == null) provider = prefs.activeProvider();
        final Provider target = provider;
        boolean canApi = target != null && target.official()
                && target.apiKey != null && !target.apiKey.trim().isEmpty();
        if (!canApi) {
            startSystemRecognizer();
            return;
        }
        if (!recorder.isRecording()
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (recorder.isRecording()) {
            File wav = recorder.stop();
            inputBar.setRecording(false);
            if (wav == null) {
                Ui.toast(this, getString(R.string.voice_too_short));
                return;
            }
            Ui.toast(this, getString(R.string.voice_recognizing));
            io.execute(() -> {
                String[] err = new String[1];
                String text = Api.asr(target, wav, err);
                main.post(() -> {
                    if (text != null && !text.trim().isEmpty()) {
                        inputBar.setText(text.trim());
                    } else {
                        Ui.toast(MainActivity.this, getString(R.string.voice_failed,
                                err[0] == null ? "" : err[0]));
                    }
                });
            });
            return;
        }
        File out = new File(getCacheDir(), "asr-" + System.currentTimeMillis() + ".wav");
        if (!recorder.start(out)) {
            Ui.toast(this, getString(R.string.voice_unavailable));
            return;
        }
        inputBar.setRecording(true);
        Ui.toast(this, getString(R.string.voice_recording));
    }

    private void startSystemRecognizer() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_input));
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.voice_unavailable));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MIC) {
            boolean granted = results.length > 0
                    && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted) {
                onVoice();
            } else {
                Ui.toast(this, getString(R.string.voice_permission_denied));
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_VOICE) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                inputBar.appendText(results.get(0));
                inputBar.focusInput();
            }
            return;
        }
        if (requestCode == REQ_EXPORT) {
            final Uri target = data.getData();
            if (target == null) return;
            io.execute(() -> {
                try {
                    java.io.OutputStream os = getContentResolver().openOutputStream(target);
                    if (os == null) throw new Exception("无法写入目标文件");
                    com.serein.chat.data.HistoryArchive.Result res =
                            com.serein.chat.data.HistoryArchive.exportArchive(db, os);
                    os.close();
                    main.post(() -> Ui.toast(this, getString(R.string.export_done,
                            res.sessions, res.messages)));
                } catch (Exception e) {
                    main.post(() -> Ui.toast(this, getString(R.string.export_failed,
                            String.valueOf(e.getMessage()))));
                }
            });
            return;
        }
        if (requestCode == REQ_IMPORT) {
            final Uri source = data.getData();
            if (source == null) return;
            Sheets.confirm(this, getString(R.string.import_confirm_title),
                    getString(R.string.import_confirm_desc), () -> {
                        io.execute(() -> {
                            try {
                                java.io.InputStream is = getContentResolver().openInputStream(source);
                                com.serein.chat.data.HistoryArchive.Result res =
                                        com.serein.chat.data.HistoryArchive.importArchive(db, is);
                                if (is != null) is.close();
                                main.post(() -> {
                                    if (res.error != null) {
                                        Ui.toast(this, getString(R.string.import_failed, res.error));
                                        return;
                                    }
                                    sessionId = null;
                                    sessionTitle = "";
                                    messages.clear();
                                    list.removeAllViews();
                                    updateWelcome();
                                    refreshDrawer();
                                    Ui.toast(this, getString(R.string.import_done,
                                            res.sessions, res.messages));
                                });
                            } catch (Exception e) {
                                main.post(() -> Ui.toast(this, getString(R.string.import_failed,
                                        String.valueOf(e.getMessage()))));
                            }
                        });
                    });
            return;
        }
        if (requestCode == REQ_BG) {
            try {
                InputStream in = getContentResolver().openInputStream(data.getData());
                if (in != null) {
                    File dest = new File(getFilesDir(), "background.png");
                    FileOutputStream out = new FileOutputStream(dest);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    out.close();
                    prefs.setBackgroundImage(dest.getAbsolutePath());
                    rebuildForTheme();
                }
            } catch (Exception e) {
                Ui.toast(this, getString(R.string.open_link_failed));
            }
            return;
        }
        if (requestCode == REQ_FILES) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            for (Uri uri : uris) {
                if (pendingFiles.size() >= MAX_FILES) break;
                addAttachment(uri);
            }
        }
    }

    private void addAttachment(Uri uri) {
        String name = queryName(uri);
        String mime = getContentResolver().getType(uri);
        Models.FileRef ref = new Models.FileRef();
        ref.name = name;
        ref.mime = mime == null ? "" : mime;
        ref.image = ref.mime.startsWith("image/");
        ref.status = "uploading";
        pendingFiles.add(ref);
        inputBar.setFiles(pendingFiles);

        if (provider == null) provider = prefs.activeProvider();
        final Provider target = provider;
        if (target.apiKey == null || target.apiKey.trim().isEmpty()) {
            ref.status = "failed";
            inputBar.setFiles(pendingFiles);
            Ui.toast(this, getString(R.string.api_key_start));
            return;
        }
        io.execute(() -> {
            try {
                File cache = new File(getCacheDir(), "attach-" + System.currentTimeMillis() + "-" + name);
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new Exception("无法读取文件");
                FileOutputStream out = new FileOutputStream(cache);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                out.close();
                ref.localPath = cache.getAbsolutePath();
                if (ref.image) {
                    Provider.ModelInfo info = currentModelInfo();
                    if (info != null && !info.vision) {
                        throw new Exception(getString(R.string.no_vision_hint));
                    }
                }
                if (target.official() && Provider.PROTO_ANTHROPIC.equals(target.protocol)) {
                    // DeepSeek 走 Files API 换取 file_id；purpose 决定解析方式
                    String lower = name.toLowerCase();
                    boolean textFile = ref.image == false && (lower.endsWith(".txt") || lower.endsWith(".md")
                            || lower.endsWith(".csv") || lower.endsWith(".json") || lower.endsWith(".log")
                            || lower.endsWith(".xml") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                            || lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".js")
                            || lower.endsWith(".ts") || lower.endsWith(".kt"));
                    String purpose = ref.image ? null
                            : textFile ? Api.PURPOSE_USER_DATA : Api.PURPOSE_TEXT;
                    ref.serverId = Api.uploadFile(target, cache, ref.mime, purpose, cancelFlag);
                }
                // 其它服务商：不预上传，发送时按 base64 内联（需模型具备视觉能力）
                ref.status = "ready";
                main.post(() -> inputBar.setFiles(pendingFiles));
            } catch (Exception e) {
                ref.status = "failed";
                ref.error = e.getMessage();
                main.post(() -> {
                    inputBar.setFiles(pendingFiles);
                    Ui.toast(MainActivity.this, getString(R.string.attach_failed,
                            e.getMessage() == null ? "" : e.getMessage()));
                });
            }
        });
    }

    private String queryName(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) {
                            String n = c.getString(idx);
                            if (n != null && !n.isEmpty()) return n;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {
        }
        String p = uri.getLastPathSegment();
        return p == null ? "file" : p;
    }

    // ------------------------------------------------------------------ 抽屉与设置

    private void openDrawer() {
        refreshDrawer();
        drawerOpen = true;
        scrim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(200).start();
        drawer.animate().translationX(0f).setDuration(220).start();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        scrim.animate().alpha(0f).setDuration(160).start();
        drawer.animate().translationX(-drawer.panelWidth()).setDuration(200)
                .withEndAction(() -> {
                    drawer.setVisibility(View.GONE);
                    scrim.setVisibility(View.GONE);
                }).start();
    }

    private void openSettings() {
        provider = prefs.activeProvider();
        Sheets.settings(this, theme, modalHost, prefs, this);
    }

    @Override
    public void onNewChat() {
        newChat();
    }

    @Override
    public void onOpenSession(Models.Session s) {
        openSession(s);
    }

    @Override
    public void onRenameSession(Models.Session s) {
        Sheets.rename(this, theme, s.title, title -> {
            String t = title.isEmpty() ? getString(R.string.untitled_chat) : title;
            db.renameSession(s.id, t);
            if (s.id.equals(sessionId)) {
                sessionTitle = t;
                headerTitle.setText(t);
            }
            refreshDrawer();
        });
    }

    @Override
    public void onDeleteSession(Models.Session s) {
        Sheets.confirm(this, getString(R.string.delete), getString(R.string.delete_all_sessions_desc),
                () -> {
                    db.deleteSession(s.id);
                    if (s.id.equals(sessionId)) {
                        sessionId = null;
                        sessionTitle = "";
                        messages.clear();
                        list.removeAllViews();
                        updateWelcome();
                    }
                    refreshDrawer();
                });
    }

    @Override
    public void onSettings() {
        closeDrawer();
        main.postDelayed(this::openSettings, 220);
    }

    @Override
    public void onCloseDrawer() {
        closeDrawer();
    }

    // Sheets.SettingsHost

    @Override
    public void onApiKeySaved(String key) {
        if (key != null && !key.trim().isEmpty()) {
            io.execute(() -> {
                String err = Api.verifyKey(provider);
                main.post(() -> {
                    if (err == null) {
                        Ui.toast(this, getString(R.string.api_key_valid));
                    } else {
                        Ui.toast(this, getString(R.string.api_key_verification_failed, err));
                    }
                });
            });
        }
    }

    @Override
    public void onLocaleChanged(String lang) {
        prefs.setLocale(lang);
        java.util.Locale locale = "en".equals(lang)
                ? java.util.Locale.ENGLISH : java.util.Locale.SIMPLIFIED_CHINESE;
        java.util.Locale.setDefault(locale);
        android.content.res.Configuration cfg =
                new android.content.res.Configuration(getResources().getConfiguration());
        cfg.setLocale(locale);
        getResources().updateConfiguration(cfg, getResources().getDisplayMetrics());
        modalHost.removeAllViews();
        main.post(this::rebuildForTheme);
    }

    @Override
    public void onExportHistory() {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, "deepseek_data-" + stamp + ".zip");
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.export_failed, String.valueOf(e.getMessage())));
        }
    }

    @Override
    public void onImportHistory() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.import_failed, String.valueOf(e.getMessage())));
        }
    }

    @Override
    public void onProviderChanged() {
        provider = prefs.activeProvider();
        refreshBalance();
        rebuildForTheme();
    }

    @Override
    public void onModelChanged(String model) {
        // 新请求即生效
    }

    @Override
    public void onThemeChanged(int themePref) {
        boolean dark = Theme.resolveDark(this, themePref);
        if (dark != theme.dark) {
            modalHost.removeAllViews();
            main.post(this::rebuildForTheme);
        }
    }

    @Override
    public void onDeleteAll() {
        Sheets.confirm(this, getString(R.string.delete_all_sessions),
                getString(R.string.delete_all_sessions_desc), () -> {
                    db.deleteAll();
                    sessionId = null;
                    sessionTitle = "";
                    messages.clear();
                    list.removeAllViews();
                    updateWelcome();
                    refreshDrawer();
                    Ui.toast(this, getString(R.string.deleted));
                });
    }

    @Override
    public void onClose() {
        modalHost.removeAllViews();
    }
}
