package app.openauthenticator.android;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.biometric.BiometricPrompt;
import androidx.biometric.BiometricManager;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import javax.crypto.Cipher;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends FragmentActivity {
    private static final int PICK_IMAGE = 1, PICK_TEXT = 2, SAVE_IMAGE = 3;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VaultStore store;
    private DeviceUnlock deviceUnlock;
    private LinearLayout root, body;
    private EditText search;
    private UiStyle style;
    private UiStyle.CountdownRing countdown;
    private final List<TextView> codeViews = new ArrayList<>();
    private final Set<String> revealed = new HashSet<>();
    private boolean settingsOpen;
    private boolean aboutOpen;
    private int settingsScrollY;
    private boolean updateChecking;
    private String updateMessage = "";
    private UpdateChecker.Result updateResult;
    private SettingsDraft settingsDraft;
    private EditText settingsUrl, settingsUser, settingsPass, settingsPath, settingsLock, settingsClip;
    private CheckBox settingsClearPass;
    private String query = "";

    private static final class SettingsDraft {
        String url, user, pass, path, lock, clip;
        boolean clearPass;
    }
    private Bitmap pendingExportImage;
    private String lastCopied;
    private boolean foreground;
    private boolean autoUnlockInProgress;
    private boolean showCodesByDefault;
    private boolean autoBiometricPrompted;
    private int openDialogs;

    interface Task { void run() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new VaultStore(this);
        deviceUnlock = new DeviceUnlock(this);
        style = new UiStyle(getPreferences(MODE_PRIVATE).getBoolean("dark_theme", false));
        showCodesByDefault = getPreferences(MODE_PRIVATE).getBoolean("show_codes_by_default", false);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        setContentView(root);
        autoUnlockInProgress = store.exists() && deviceUnlock.directEnabled();
        render();
        if (autoUnlockInProgress) {
            worker.submit(() -> {
                try {
                    store.unlock(deviceUnlock.directPassword());
                    runOnUiThread(() -> { autoUnlockInProgress = false; render(); handleIntent(getIntent()); });
                } catch (Exception error) {
                    deviceUnlock.disableDirect();
                    runOnUiThread(() -> {
                        autoUnlockInProgress = false; render();
                        toast("免密码凭据已失效，请用主密码解锁");
                    });
                }
            });
        }
        handler.postDelayed(this::tick, 1000);
    }

    @Override protected void onResume() { super.onResume(); foreground = true; render(); handleIntent(getIntent()); }
    @Override protected void onPause() { if (settingsOpen && !aboutOpen) captureSettingsDraft(); foreground = false; super.onPause(); }
    @Override protected void onDestroy() { store.lock(); worker.shutdownNow(); super.onDestroy(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && store != null && store.unlocked()) {
            try { store.checkTimeout(); store.touch(); }
            catch (Exception error) { render(); return true; }
        }
        return super.dispatchTouchEvent(event);
    }
    @Override public void onBackPressed() {
        if (aboutOpen) { aboutOpen = false; render(); }
        else if (settingsOpen) { settingsOpen = false; settingsDraft = null; render(); }
        else super.onBackPressed();
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getData() == null || !store.unlocked()) return;
        if (!"android.intent.action.VIEW".equals(intent.getAction())) return;
        String uri = intent.getDataString(); intent.setData(null); importContent(uri);
    }

    private void run(Task task, Runnable done) {
        worker.submit(() -> {
            try { task.run(); runOnUiThread(() -> { if (done != null) done.run(); }); }
            catch (Exception error) { runOnUiThread(() -> toast(error.getMessage() == null ? "操作失败" : error.getMessage())); }
        });
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void applyWindowColors() {
        root.setBackgroundColor(style.bg);
        getWindow().setStatusBarColor(style.bg);
        getWindow().setNavigationBarColor(style.bg);
        getWindow().getDecorView().setSystemUiVisibility(style.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void updateScreenshotProtection() {
        boolean homePage = store != null && store.unlocked() && !settingsOpen && !aboutOpen
                && !autoUnlockInProgress;
        if (root != null) root.setVisibility(homePage && openDialogs > 0 ? View.INVISIBLE : View.VISIBLE);
        if (homePage && openDialogs == 0) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private AlertDialog.Builder dialog() {
        openDialogs++;
        updateScreenshotProtection();
        return new AlertDialog.Builder(this, style.dark
                ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setOnDismissListener(ignored -> {
                    openDialogs--;
                    updateScreenshotProtection();
                });
    }
    private void showSaveResult(String message) {
        if (message.contains("同步失败") || message.contains("同步标记保存失败")) {
            dialog().setTitle("本地已保存").setMessage(message).setPositiveButton("知道了", null).show();
        } else toast(message);
    }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(style.text);
        if (bold) view.setTypeface(null, Typeface.BOLD); return view;
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setTextSize(14); b.setAllCaps(false); b.setTextColor(style.text);
        b.setBackground(UiStyle.rounded(style.fill, this, 10)); b.setMinHeight(dp(44)); b.setElevation(0);
        b.setOnClickListener(v -> action.run()); return b;
    }
    private Button primaryButton(String label, Runnable action) {
        Button b = button(label, action); b.setTextColor(Color.WHITE); b.setTypeface(null, Typeface.BOLD);
        b.setBackground(UiStyle.rounded(style.accent, this, 11)); return b;
    }
    private UiStyle.IconButton iconButton(String kind, String description, boolean filled, Runnable action) {
        return new UiStyle.IconButton(this, style, kind, description, filled, action);
    }
    private void icon(LinearLayout parent, String kind, String description, boolean filled, Runnable action, int size) {
        UiStyle.IconButton view = iconButton(kind, description, filled, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(size), dp(size)); params.leftMargin = dp(7);
        parent.addView(view, params);
    }
    private EditText input(String hint, String value, boolean secret) {
        EditText field = new EditText(this); field.setSingleLine(true); field.setText(value); field.setHint(hint); field.setTextSize(15);
        if (secret) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setTextColor(style.text); field.setHintTextColor(style.muted);
        field.setBackground(UiStyle.rounded(style.fill, this, 10));
        field.setPadding(dp(13), dp(10), dp(13), dp(10)); field.setMinHeight(dp(46));
        field.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (store != null && store.unlocked()) {
                    try { store.checkTimeout(); store.touch(); }
                    catch (Exception error) { render(); }
                }
            }
            public void afterTextChanged(android.text.Editable value) {}
        });
        return field;
    }
    private LinearLayout column() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); return c; }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    private void add(LinearLayout parent, View child) { parent.addView(child, new LinearLayout.LayoutParams(-1, -2)); }
    private void equal(LinearLayout parent, View child) { parent.addView(child, new LinearLayout.LayoutParams(0, -2, 1)); }
    private void gap(LinearLayout parent, int height) { View space = new View(this); parent.addView(space, new LinearLayout.LayoutParams(1, dp(height))); }
    private TextView muted(String value, int size) { TextView view = text(value, size, false); view.setTextColor(style.muted); return view; }
    private TextView sectionTitle(String value) {
        TextView title = muted(value, 12); title.setTypeface(null, Typeface.BOLD); title.setLetterSpacing(.05f);
        title.setPadding(dp(4), dp(17), 0, dp(7)); return title;
    }
    private LinearLayout sectionCard(LinearLayout parent) {
        LinearLayout card = column(); card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(UiStyle.rounded(style.surface, this, 13)); add(parent, card); return card;
    }
    private EditText labeledField(LinearLayout parent, String label, String hint, String value, boolean secret) {
        TextView title = muted(label, 12); title.setPadding(0, 0, 0, dp(6)); add(parent, title);
        EditText field = input(hint, value, secret); add(parent, field); gap(parent, 12); return field;
    }

    private void render() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root.setVisibility(View.VISIBLE);
        root.removeAllViews();
        root.setPadding(dp(16), dp(14), dp(16), dp(12)); applyWindowColors();
        codeViews.clear(); countdown = null; body = null;
        if (autoUnlockInProgress) { unlockingScreen(); return; }
        if (!settingsOpen) {
            settingsUrl = null; settingsUser = null; settingsPass = null; settingsPath = null;
            settingsLock = null; settingsClip = null; settingsClearPass = null;
        }
        if (!store.exists()) { settingsOpen = false; aboutOpen = false; settingsDraft = null; authScreen(true); return; }
        if (!store.unlocked()) { settingsOpen = false; aboutOpen = false; settingsDraft = null; revealed.clear(); authScreen(false); return; }
        try { store.checkLock(); } catch (Exception error) { settingsOpen = false; aboutOpen = false; settingsDraft = null; revealed.clear(); authScreen(false); return; }
        autoBiometricPrompted = false;
        if (settingsOpen) { if (aboutOpen) renderAboutPage(); else renderSettingsPage(); return; }
        renderHome();
        updateScreenshotProtection();
    }

    private void unlockingScreen() {
        LinearLayout content = column(); content.setGravity(Gravity.CENTER);
        TextView title = text("验证器", 24, true); title.setGravity(Gravity.CENTER); add(content, title); gap(content, 8);
        TextView hint = muted("正在解锁保险库…", 13); hint.setGravity(Gravity.CENTER); add(content, hint);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void renderHome() {
        LinearLayout header = row();
        LinearLayout titleBlock = column();
        add(titleBlock, text("验证码", 28, true));
        TextView subtitle = muted(showCodesByDefault ? "点验证码复制 · 也可复制邮箱" : "点验证码显示 · 复制验证码或邮箱", 12);
        subtitle.setPadding(0, dp(3), 0, 0); add(titleBlock, subtitle);
        equal(header, titleBlock);
        countdown = new UiStyle.CountdownRing(this, style);
        header.addView(countdown, new LinearLayout.LayoutParams(dp(36), dp(36)));
        countdown.update(); add(root, header); gap(root, 17);

        LinearLayout toolbar = row();
        LinearLayout searchBox = row(); searchBox.setPadding(dp(8), 0, dp(8), 0);
        searchBox.setBackground(UiStyle.rounded(style.fill, this, 10));
        searchBox.addView(iconButton("search", "搜索", false, () -> search.requestFocus()), new LinearLayout.LayoutParams(dp(25), dp(38)));
        search = input("搜索", query, false); search.setBackgroundColor(Color.TRANSPARENT);
        search.setPadding(dp(5), 0, 0, 0); search.setTextSize(15);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, dp(40), 1));
        toolbar.addView(searchBox, new LinearLayout.LayoutParams(0, dp(40), 1));
        icon(toolbar, "plus", "手动添加", true, this::addAccount, 40);
        icon(toolbar, "upload", "导入与迁移", true, this::menu, 40);
        icon(toolbar, "settings", "设置", true, this::settingsDialog, 40);
        add(root, toolbar); gap(root, 12);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        body = column(); body.setPadding(0, 0, 0, dp(92)); scroll.addView(body);
        FrameLayout content = new FrameLayout(this);
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        UiStyle.IconButton scanButton = new UiStyle.IconButton(this, style, "scan", "扫码添加", false, this::scan, Color.WHITE);
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL); circle.setColor(style.accent);
        scanButton.setBackground(circle); scanButton.setElevation(dp(7));
        FrameLayout.LayoutParams scanParams = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.END | Gravity.BOTTOM);
        scanParams.rightMargin = dp(12); scanParams.bottomMargin = dp(20);
        content.addView(scanButton, scanParams);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int before, int count) { query = s.toString(); fillAccounts(query); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        fillAccounts(query);
    }

    private void authScreen(boolean setup) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout content = column(); content.setGravity(Gravity.CENTER_VERTICAL); content.setPadding(dp(12), 0, dp(12), dp(20));
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout card = sectionCard(content); card.setPadding(dp(22), dp(24), dp(22), dp(22));
        TextView mark = text("验", 23, true); mark.setTextColor(Color.WHITE); mark.setGravity(Gravity.CENTER);
        mark.setBackground(UiStyle.rounded(style.accent, this, 14));
        LinearLayout markRow = row(); markRow.setGravity(Gravity.CENTER);
        markRow.addView(mark, new LinearLayout.LayoutParams(dp(54), dp(54))); add(card, markRow); gap(card, 14);
        TextView title = text(setup ? "创建加密保险库" : "验证器", 22, true); title.setGravity(Gravity.CENTER); add(card, title);
        TextView subtitle = muted(setup ? "设置至少 8 位的主密码" : "输入主密码解锁", 13); subtitle.setGravity(Gravity.CENTER);
        add(card, subtitle); gap(card, 20);
        EditText password = labeledField(card, setup ? "新主密码" : "主密码", "请输入主密码", "", true);
        EditText confirm = null;
        if (setup) confirm = labeledField(card, "确认密码", "再次输入主密码", "", true);
        EditText finalConfirm = confirm;
        add(card, primaryButton(setup ? "创建保险库" : "解锁", () -> {
            String pass = password.getText().toString();
            if (setup && !pass.equals(finalConfirm.getText().toString())) { toast("两次密码不一致"); return; }
            run(() -> { if (setup) store.setup(pass); else store.unlock(pass); }, () -> { render(); handleIntent(getIntent()); });
        }));
        if (!setup && deviceUnlock.biometricEnabled()) {
            gap(card, 10);
            add(card, button("使用指纹解锁", this::unlockWithBiometric));
            if (!deviceUnlock.directEnabled() && !autoBiometricPrompted && foreground) {
                autoBiometricPrompted = true;
                root.post(() -> {
                    if (!isFinishing() && !store.unlocked() && deviceUnlock.biometricEnabled()
                            && !deviceUnlock.directEnabled()) unlockWithBiometric();
                });
            }
        }
        if (setup) { gap(card, 10); add(card, button("从 WebDAV 恢复", this::restoreDialog)); }
    }

    private void fillAccounts(String query) {
        if (body == null || !store.unlocked()) return;
        body.removeAllViews(); codeViews.clear();
        try {
            JSONArray accounts = store.accounts();
            Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject a = accounts.getJSONObject(i);
                String label = a.optString("issuer") + " " + a.optString("name") + " " + a.optString("email") + " " + a.optString("notes");
                if (!label.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT).trim())) continue;
                String issuer = a.optString("issuer").isEmpty() ? "其他" : a.optString("issuer");
                groups.computeIfAbsent(issuer, ignored -> new ArrayList<>()).add(a);
            }
            for (Map.Entry<String, List<JSONObject>> group : groups.entrySet()) {
                add(body, sectionTitle(group.getKey().toUpperCase(Locale.ROOT)));
                LinearLayout card = column(); card.setBackground(UiStyle.rounded(style.surface, this, 13));
                card.setClipToOutline(true); add(body, card);
                List<JSONObject> items = group.getValue();
                for (int i = 0; i < items.size(); i++) {
                    add(card, accountRow(items.get(i)));
                    if (i < items.size() - 1) {
                        View divider = new View(this); divider.setBackgroundColor(style.line);
                        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1)); line.leftMargin = dp(12); card.addView(divider, line);
                    }
                }
            }
            if (groups.isEmpty()) {
                gap(body, 45);
                LinearLayout empty = sectionCard(body); empty.setPadding(dp(22), dp(24), dp(22), dp(24));
                boolean hasAccounts = accounts.length() > 0;
                TextView title = text(hasAccounts ? "没有匹配账号" : "还没有账号", 17, true);
                title.setGravity(Gravity.CENTER); add(empty, title); gap(empty, 6);
                TextView hint = muted(hasAccounts ? "换个关键词试试" : "点右下角扫码按钮，或手动添加 TOTP 账号", 13);
                hint.setGravity(Gravity.CENTER); add(empty, hint);
                gap(empty, 18);
                add(empty, primaryButton(hasAccounts ? "清除搜索" : "手动添加", hasAccounts ? () -> search.setText("") : this::addAccount));
            }
        } catch (Exception error) { toast("无法显示账号"); }
    }

    private View accountRow(JSONObject a) {
        LinearLayout item = column(); item.setPadding(dp(12), dp(12), dp(10), dp(8));
        LinearLayout top = row();
        String issuer = a.optString("issuer"), name = a.optString("name"), email = a.optString("email");
        String title = !email.isEmpty() && email.equals(name) ? (issuer.isEmpty() ? name : issuer) : (name.isEmpty() ? issuer : name);
        if (title.isEmpty()) title = "未命名";
        TextView avatar = text(title.substring(0, 1).toUpperCase(Locale.ROOT), 14, true);
        avatar.setTextColor(Color.WHITE); avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(UiStyle.avatar(this, Math.floorMod(title.hashCode(), 360)));
        top.addView(avatar, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout meta = column(); meta.setPadding(dp(9), 0, dp(2), 0);
        TextView nameView = text(title, 15, true); nameView.setSingleLine(true);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END); add(meta, nameView);
        if (!email.isEmpty()) {
            TextView emailView = text(email, 12, false); emailView.setSingleLine(true);
            emailView.setEllipsize(android.text.TextUtils.TruncateAt.END); emailView.setTextColor(emailColor(email));
            emailView.setOnClickListener(v -> { copy(email, "邮箱", store.settings().optLong("clipboard_clear_seconds", 30)); toast("已复制邮箱"); });
            add(meta, emailView);
        }
        top.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
        add(item, top);
        TextView code = text(a.optInt("digits") == 8 ? "•••• ••••" : "••• •••", 32, true);
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); code.setTextColor(style.hidden);
        code.setSingleLine(true);
        code.setAutoSizeTextTypeUniformWithConfiguration(24, 32, 2, TypedValue.COMPLEX_UNIT_SP);
        code.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        code.setPadding(0, dp(5), 0, dp(5)); code.setMinHeight(dp(54)); code.setTag(a);
        code.setContentDescription(showCodesByDefault ? "验证码，点击复制" : "点按显示验证码");
        code.setOnClickListener(v -> {
            if (showCodesByDefault || !revealed.add(a.optString("id"))) copyCode(a);
            else showCode(code, a);
        });
        if (showCodesByDefault || revealed.contains(a.optString("id"))) showCode(code, a);
        LinearLayout.LayoutParams codeLayout = new LinearLayout.LayoutParams(-1, -2);
        codeLayout.leftMargin = dp(45);
        item.addView(code, codeLayout); codeViews.add(code);
        LinearLayout actions = row(); actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        String notes = a.optString("notes");
        if (!notes.isEmpty()) {
            TextView noteView = muted(notes, 11); noteView.setSingleLine(true);
            noteView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            actions.addView(noteView, new LinearLayout.LayoutParams(0, -2, 1));
        } else {
            View spacer = new View(this); actions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        }
        icon(actions, "copy", "复制验证码", false, () -> copyCode(a), 30);
        icon(actions, "qr", "账号二维码", false, () -> showQr(a), 30);
        add(item, actions);
        return new SwipeAccountLayout(item, a, title);
    }

    private final class SwipeAccountLayout extends FrameLayout {
        private final View content;
        private final JSONObject account;
        private final int touchSlop;
        private float startX, startY;
        private boolean swiping;

        SwipeAccountLayout(View content, JSONObject account, String title) {
            super(MainActivity.this);
            this.content = content;
            this.account = account;
            touchSlop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
            setClipChildren(true);
            setClickable(true);
            setContentDescription(title + "，右滑编辑，左滑移除");
            LinearLayout actions = row();
            TextView edit = text("编辑", 14, true);
            edit.setTextColor(Color.WHITE); edit.setGravity(Gravity.CENTER);
            edit.setBackgroundColor(style.accent);
            actions.addView(edit, new LinearLayout.LayoutParams(0, -1, 1));
            TextView remove = text("移除", 14, true);
            remove.setTextColor(Color.WHITE); remove.setGravity(Gravity.CENTER);
            remove.setBackgroundColor(style.danger);
            actions.addView(remove, new LinearLayout.LayoutParams(0, -1, 1));
            addView(actions, new FrameLayout.LayoutParams(-1, -1));
            content.setBackgroundColor(style.surface);
            addView(content, new FrameLayout.LayoutParams(-1, -2));
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX(); startY = event.getY(); swiping = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - startX, dy = event.getY() - startY;
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        swiping = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (swiping) {
                        float dx = event.getX() - startX;
                        float limit = getWidth() / 2f;
                        content.setTranslationX(Math.max(-limit, Math.min(limit, dx)));
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (swiping) {
                        float distance = event.getX() - startX;
                        float required = getWidth() / 2f;
                        content.animate().translationX(0).setDuration(140).withEndAction(() -> {
                            if (distance <= -required) confirmDelete(account);
                            else if (distance >= required) editAccount(account);
                        }).start();
                        swiping = false;
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    if (swiping) {
                        content.animate().translationX(0).setDuration(140).start();
                        swiping = false;
                        return true;
                    }
                    break;
            }
            return super.onTouchEvent(event);
        }
    }

    private int emailColor(String email) {
        int[] colors = style.dark
                ? new int[]{0xff64b5ff, 0xffff9f0a, 0xff32d74b, 0xffff6482, 0xffbf5af2,
                    0xff5ac8fa, 0xffffd60a, 0xff5eead4, 0xffff6961, 0xffa5b4fc}
                : new int[]{0xff1d4ed8, 0xffc2410c, 0xff15803d, 0xffbe123c, 0xff7e22ce,
                    0xff0369a1, 0xffa16207, 0xff0f766e, 0xffb91c1c, 0xff4338ca};
        return colors[Math.floorMod(email.toLowerCase(Locale.ROOT).hashCode(), colors.length)];
    }

    private void copyCode(JSONObject a) {
        try {
            String value = Totp.code(a.getString("secret"), a.getString("algorithm"), a.getInt("digits"), a.getLong("period"), System.currentTimeMillis() / 1000);
            copy(value, "验证码", store.settings().optLong("clipboard_clear_seconds", 30)); toast("已复制验证码");
        } catch (Exception error) { toast(error.getMessage()); }
    }

    private void showCode(TextView view, JSONObject a) {
        try {
            long now = System.currentTimeMillis() / 1000;
            String code = Totp.code(a.getString("secret"), a.getString("algorithm"), a.getInt("digits"), a.getLong("period"), now);
            String formatted = code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code.substring(0, 4) + " " + code.substring(4);
            view.setText(formatted);
            view.setTextColor(a.getLong("period") - now % a.getLong("period") <= 5 ? style.danger : style.text);
            view.setContentDescription("验证码，点击复制");
        } catch (Exception error) { toast(error.getMessage()); }
    }

    private void tick() {
        if (foreground && store.unlocked()) {
            try {
                store.checkTimeout();
                if (countdown != null) countdown.update();
                for (TextView code : codeViews) {
                    Object account = code.getTag();
                    if (account instanceof JSONObject && (showCodesByDefault || revealed.contains(((JSONObject) account).optString("id")))) {
                        showCode(code, (JSONObject) account);
                    }
                }
            } catch (Exception error) { render(); }
        }
        handler.postDelayed(this::tick, 1000);
    }

    private void addAccount() {
        LinearLayout form = column(); form.setPadding(dp(18), dp(8), dp(18), dp(10));
        add(form, muted("输入账号名称和设置密钥，即可添加标准时间验证码。", 13)); gap(form, 16);
        EditText name = labeledField(form, "账号名称", "例如 GitHub:you@example.com", "", false);
        EditText secret = labeledField(form, "设置密钥", "粘贴 Base32 密钥", "", false);
        secret.setSingleLine(false);
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        secret.setMinLines(3); secret.setMaxLines(5); secret.setMinHeight(dp(112));
        secret.setHorizontallyScrolling(false);
        secret.setGravity(Gravity.TOP | Gravity.START);
        secret.setTypeface(Typeface.MONOSPACE);
        secret.setPadding(dp(13), dp(14), dp(13), dp(14));
        secret.setVerticalScrollBarEnabled(true);
        add(form, muted("可分行粘贴，空格和换行会自动忽略。", 12)); gap(form, 10);
        add(form, muted("密钥类型：基于时间 · 6 位 · 每 30 秒更新", 12));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog popup = dialog().setTitle("输入设置密钥").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("添加", null).create();
        popup.setOnShowListener(dialog -> popup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty()) { name.setError("请输入账号名称"); return; }
            String normalized;
            try { normalized = Totp.normalize(secret.getText().toString()); }
            catch (Exception error) { secret.setError(error.getMessage()); return; }
            int colon = label.indexOf(':');
            String issuer = colon > 0 ? label.substring(0, colon).trim() : "";
            String accountName = colon > 0 ? label.substring(colon + 1).trim() : label;
            if (accountName.isEmpty()) { name.setError("请输入账号名称"); return; }
            String key = normalized;
            popup.dismiss();
            String[] message = new String[1];
            run(() -> {
                JSONObject account = new JSONObject()
                        .put("issuer", issuer).put("name", accountName)
                        .put("email", accountName.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") ? accountName : "")
                        .put("notes", "").put("secret", key)
                        .put("algorithm", "SHA1").put("digits", 6).put("period", 30);
                store.upsert(account);
                message[0] = autoUploadNewAccounts();
            }, () -> { render(); showSaveResult(message[0]); });
        }));
        popup.show();
    }

    private void editAccount(JSONObject existing) {
        LinearLayout form = column(); form.setPadding(dp(18), dp(8), dp(18), dp(8));
        EditText name = labeledField(form, "账号名称", "账号名称", existing.optString("name"), false);
        EditText email = labeledField(form, "邮箱", "可选", existing.optString("email"), false);
        EditText issuer = labeledField(form, "分组", "例如 OpenAI", existing.optString("issuer"), false);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog popup = dialog().setTitle("编辑账号").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        popup.setOnShowListener(dialog -> popup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String issuerValue = issuer.getText().toString().trim();
                    String nameValue = name.getText().toString().trim();
                    String emailValue = email.getText().toString().trim();
                    if (issuerValue.isEmpty() && nameValue.isEmpty()) {
                        name.setError("请填写账号名称或分组"); return;
                    }
                    popup.dismiss();
                    String[] message = new String[1];
                    run(() -> {
                        JSONObject a = new JSONObject(existing.toString());
                        a.put("issuer", issuerValue).put("name", nameValue).put("email", emailValue);
                        store.upsert(a);
                        message[0] = "账号已保存；编辑后的内容可在设置中上传";
                    }, () -> { render(); showSaveResult(message[0]); });
                }));
        popup.show();
    }

    private void confirmDelete(JSONObject a) {
        dialog().setTitle("删除账号？").setMessage(a.optString("issuer") + " " + a.optString("name"))
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> run(() -> store.delete(a.getString("id")), this::render)).show();
    }

    private void menu() {
        LinearLayout content = column(); content.setPadding(dp(18), dp(8), dp(18), dp(8));
        add(content, muted("选择导入来源，或生成给另一台设备扫描的迁移二维码。", 13)); gap(content, 16);
        AlertDialog[] popup = new AlertDialog[1];
        menuAction(content, "从二维码图片导入", "识别相册中的账号或 Google 迁移二维码", () -> {
            popup[0].dismiss(); pick("image/*", PICK_IMAGE);
        });
        menuAction(content, "从备份文件导入", "读取 JSON、TXT 或 otpauth 链接文件", () -> {
            popup[0].dismiss(); pick("*/*", PICK_TEXT);
        });
        menuAction(content, "迁移到其他设备", "生成 Google 验证器兼容二维码", () -> {
            popup[0].dismiss(); migrationDialog();
        });
        popup[0] = dialog().setTitle("导入与迁移").setView(content).setNegativeButton("关闭", null).create();
        popup[0].show();
    }

    private void menuAction(LinearLayout parent, String title, String detail, Runnable action) {
        LinearLayout card = column(); card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(UiStyle.rounded(style.fill, this, 11));
        add(card, text(title, 15, true)); gap(card, 4); add(card, muted(detail, 12));
        card.setOnClickListener(view -> action.run());
        card.setClickable(true); card.setFocusable(true);
        add(parent, card); gap(parent, 9);
    }

    private void scan() {
        new IntentIntegrator(this).setCaptureActivity(PortraitScanActivity.class)
                .setOrientationLocked(false).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("").setBeepEnabled(false).initiateScan();
    }

    private void importContent(String content) {
        if (content == null) return;
        String[] message = new String[1];
        run(() -> {
            List<JSONObject> parsed = OtpUri.parse(content);
            message[0] = importAndSync(parsed);
        }, () -> { render(); showSaveResult(message[0]); });
    }

    private String importAndSync(List<JSONObject> parsed) throws Exception {
        int count = store.importAccounts(parsed);
        if (count == 0) return "没有新账号，未进行同步";
        return "已导入 " + count + " 个账号；" + autoUploadNewAccounts();
    }

    private String autoUploadNewAccounts() {
        JSONObject settings = store.settings();
        if (settings.optString("webdav_url").trim().isEmpty()) return "已保存到本地；配置 WebDAV 后可同步";
        String etag;
        try { etag = WebDav.put(settings, store.encryptedBytes()); }
        catch (Exception error) { return "已保存到本地，但 WebDAV 同步失败：" + error.getMessage(); }
        try { store.setEtag(etag); }
        catch (Exception error) { return "已上传 WebDAV，但本地同步标记保存失败：" + error.getMessage(); }
        return "已保存并同步到 WebDAV";
    }

    private void pick(String mime, int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType(mime); intent.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(intent, request);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        IntentResult scan = IntentIntegrator.parseActivityResult(request, result, data);
        if (scan != null) { if (scan.getContents() != null) importContent(scan.getContents()); return; }
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (request == SAVE_IMAGE) pendingExportImage = null;
            return;
        }
        Uri uri = data.getData();
        if (request == SAVE_IMAGE) {
            Bitmap image = pendingExportImage; pendingExportImage = null;
            if (image == null) return;
            run(() -> {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null || !image.compress(Bitmap.CompressFormat.PNG, 100, out))
                        throw new IllegalStateException("保存图片失败");
                }
            }, () -> toast("迁移二维码图片已保存"));
        } else if (request == PICK_TEXT) {
            String[] message = new String[1];
            run(() -> {
                byte[] bytes = readUri(uri, VaultCodec.LIMIT); String content = new String(bytes, StandardCharsets.UTF_8);
                message[0] = importAndSync(OtpUri.parse(content));
            }, () -> { render(); showSaveResult(message[0]); });
        } else if (request == PICK_IMAGE) {
            String[] message = new String[1];
            run(() -> {
                android.graphics.Bitmap bitmap;
                try (InputStream in = getContentResolver().openInputStream(uri)) { bitmap = android.graphics.BitmapFactory.decodeStream(in); }
                if (bitmap == null || (long) bitmap.getWidth() * bitmap.getHeight() > 16000000) throw new IllegalArgumentException("图片无效或过大");
                int width = bitmap.getWidth(), height = bitmap.getHeight(); int[] pixels = new int[width * height]; bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                String raw = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)))).getText();
                message[0] = importAndSync(OtpUri.parse(raw));
            }, () -> { render(); showSaveResult(message[0]); });
        }
    }

    private byte[] readUri(Uri uri, int limit) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) { if (output.size() + n > limit) throw new IllegalArgumentException("文件过大"); output.write(buffer, 0, n); }
            return output.toByteArray();
        }
    }

    private void showQr(JSONObject a) {
        try {
            String uri = OtpUri.export(a);
            ImageView image = new ImageView(this); image.setImageBitmap(qrBitmap(uri)); image.setPadding(dp(20), dp(20), dp(20), dp(20));
            dialog().setTitle("账号二维码").setView(image).setNegativeButton("关闭", null)
                    .setPositiveButton("复制链接", (d, w) -> copy(uri, "账号链接", 30)).show();
        } catch (Exception error) { toast(error.getMessage()); }
    }

    private Bitmap qrBitmap(String uri) throws Exception {
        final int size = 760;
        BitMatrix matrix = new QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, size, size);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
            pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    }

    private void migrationDialog() {
        verifyOwner("迁移到其他设备", ignored -> {
            OtpUri.MigrationExport[] export = new OtpUri.MigrationExport[1];
            run(() -> export[0] = OtpUri.migrationExport(store.accounts()), () -> showMigrationPages(export[0]));
        });
    }

    private void showMigrationPages(OtpUri.MigrationExport export) {
        if (export.pages.isEmpty()) {
            dialog().setTitle("没有可迁移的账号")
                    .setMessage("Google 迁移二维码仅支持标准 30 秒周期账号。其他账号可在首页逐个显示账号二维码。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        LinearLayout content = column(); content.setPadding(dp(18), dp(8), dp(18), dp(12));
        add(content, muted("在另一台设备的 Google 验证器中选择「转移账号」并逐张扫描。", 13)); gap(content, 12);
        if (export.excluded > 0) {
            TextView warning = muted("有 " + export.excluded + " 个非 30 秒周期账号未包含在迁移二维码中。", 12);
            warning.setTextColor(style.danger); add(content, warning); gap(content, 10);
        }
        ImageView image = new ImageView(this); image.setContentDescription("迁移二维码");
        image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setPadding(dp(8), dp(8), dp(8), dp(8));
        image.setBackground(UiStyle.rounded(Color.WHITE, this, 10));
        content.addView(image, new LinearLayout.LayoutParams(-1, dp(300))); gap(content, 10);
        TextView pageLabel = text("", 14, true); pageLabel.setGravity(Gravity.CENTER); add(content, pageLabel); gap(content, 10);
        LinearLayout navigation = row();
        Button previous = button("上一张", () -> {}), next = button("下一张", () -> {});
        equal(navigation, previous); View spacer = new View(this);
        navigation.addView(spacer, new LinearLayout.LayoutParams(dp(8), 1)); equal(navigation, next);
        if (export.pages.size() > 1) { add(content, navigation); gap(content, 10); }
        int[] page = {0}; Bitmap[] currentImage = new Bitmap[1];
        Runnable update = () -> {
            try {
                OtpUri.MigrationPage selected = export.pages.get(page[0]);
                currentImage[0] = qrBitmap(selected.uri); image.setImageBitmap(currentImage[0]);
                pageLabel.setText("第 " + (page[0] + 1) + "/" + export.pages.size() + " 张 · " + selected.count + " 个账号");
                previous.setEnabled(page[0] > 0); next.setEnabled(page[0] < export.pages.size() - 1);
            } catch (Exception error) { toast("无法显示迁移二维码：" + error.getMessage()); }
        };
        previous.setOnClickListener(view -> { if (page[0] > 0) { page[0]--; update.run(); } });
        next.setOnClickListener(view -> { if (page[0] < export.pages.size() - 1) { page[0]++; update.run(); } });
        add(content, button("保存当前二维码图片", () -> {
            if (currentImage[0] == null) return;
            pendingExportImage = currentImage[0];
            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            save.addCategory(Intent.CATEGORY_OPENABLE); save.setType("image/png");
            save.putExtra(Intent.EXTRA_TITLE, "authenticator-migration-" + (page[0] + 1) + "-of-" + export.pages.size() + ".png");
            startActivityForResult(save, SAVE_IMAGE);
        }));
        gap(content, 10);
        add(content, muted("二维码含账号密钥，请只在可信设备上打开或保存。每张最多 10 个账号。", 12));
        update.run();
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        dialog().setTitle("迁移到其他设备").setView(scroll).setPositiveButton("完成", null).show();
    }

    private void copy(String value, String label, long clearSeconds) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value)); lastCopied = value;
        if (clearSeconds > 0) handler.postDelayed(() -> {
            try { if (value.equals(lastCopied) && clipboard.hasPrimaryClip() && value.equals(clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString())) clipboard.clearPrimaryClip(); }
            catch (Exception ignored) {} }, Math.min(clearSeconds, 86400) * 1000);
    }

    private void verifyOwner(String title, java.util.function.Consumer<String> action) {
        if (deviceUnlock.biometricEnabled() && deviceUnlock.biometricAvailable(this)) {
            try {
                authenticateBiometric(deviceUnlock.biometricDecryptCipher(), title, cipher -> {
                    try {
                        String password = deviceUnlock.biometricPassword(cipher);
                        worker.submit(() -> {
                            try {
                                store.verifyPassword(password);
                                runOnUiThread(() -> action.accept(password));
                            } catch (Exception error) {
                                runOnUiThread(() -> {
                                    deviceUnlock.disableBiometric();
                                    toast("指纹凭据已失效，请使用主密码验证");
                                    showPasswordVerification(title, action);
                                });
                            }
                        });
                    } catch (Exception error) {
                        deviceUnlock.disableBiometric();
                        toast("指纹凭据已失效，请使用主密码验证");
                        showPasswordVerification(title, action);
                    }
                }, () -> showPasswordVerification(title, action));
                return;
            } catch (Exception error) {
                deviceUnlock.disableBiometric();
                toast("指纹凭据已失效，请使用主密码验证");
            }
        }
        showPasswordVerification(title, action);
    }

    private void showPasswordVerification(String title, java.util.function.Consumer<String> action) {
        EditText field = input("当前主密码", "", true);
        LinearLayout content = column(); content.setPadding(dp(20), dp(8), dp(20), 0); add(content, field);
        dialog().setTitle(title).setMessage("请先验证当前主密码")
                .setView(content).setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> {
                    String candidate = field.getText().toString();
                    run(() -> store.verifyPassword(candidate), () -> action.accept(candidate));
                }).show();
    }

    private void authenticateBiometric(Cipher cipher, String title, java.util.function.Consumer<Cipher> success) {
        authenticateBiometric(cipher, title, success, null);
    }

    private void authenticateBiometric(Cipher cipher, String title, java.util.function.Consumer<Cipher> success,
                                       Runnable passwordFallback) {
        BiometricPrompt prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        Cipher authenticated = result.getCryptoObject() == null ? null : result.getCryptoObject().getCipher();
                        if (authenticated == null) {
                            toast("指纹验证未返回加密凭据");
                            if (passwordFallback != null) passwordFallback.run();
                            return;
                        }
                        success.accept(authenticated);
                    }
                    @Override public void onAuthenticationError(int code, CharSequence message) {
                        if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON && passwordFallback != null) {
                            passwordFallback.run(); return;
                        }
                        if (code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_CANCELED
                                || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON) return;
                        toast("指纹验证失败：" + message);
                        if (passwordFallback != null) passwordFallback.run();
                    }
                });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title).setSubtitle("使用设备上已录入的指纹")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(passwordFallback == null ? "取消" : "使用主密码").build();
        prompt.authenticate(info, new BiometricPrompt.CryptoObject(cipher));
    }

    private void unlockWithBiometric() {
        try {
            if (!deviceUnlock.biometricAvailable(this)) { toast("请先在系统设置中录入指纹"); return; }
            authenticateBiometric(deviceUnlock.biometricDecryptCipher(), "解锁验证器", cipher -> {
                try {
                    String password = deviceUnlock.biometricPassword(cipher);
                    run(() -> {
                        try { store.unlock(password); }
                        catch (Exception error) { deviceUnlock.disableBiometric(); throw error; }
                    }, () -> { render(); handleIntent(getIntent()); });
                } catch (Exception error) { deviceUnlock.disableBiometric(); render(); toast("指纹凭据已失效，请用主密码解锁"); }
            });
        } catch (Exception error) { deviceUnlock.disableBiometric(); render(); toast("指纹凭据已失效，请用主密码解锁"); }
    }

    private void toggleDirectUnlock() {
        if (deviceUnlock.directEnabled()) {
            deviceUnlock.disableDirect(); captureSettingsDraft(); render(); toast("已关闭免密码进入");
            return;
        }
        verifyOwner("启用免密码进入", password -> run(() -> deviceUnlock.enableDirect(password), () -> {
            captureSettingsDraft(); render(); toast("已开启免密码进入");
        }));
    }

    private void toggleBiometricUnlock() {
        if (deviceUnlock.biometricEnabled()) {
            deviceUnlock.disableBiometric(); captureSettingsDraft(); render(); toast("已关闭指纹解锁");
            return;
        }
        if (!deviceUnlock.biometricAvailable(this)) { toast("请先在系统设置中录入指纹"); return; }
        verifyOwner("启用指纹解锁", password -> {
            try {
                authenticateBiometric(deviceUnlock.biometricEncryptCipher(), "启用指纹解锁", cipher -> {
                    try {
                        deviceUnlock.saveBiometric(cipher, password);
                        captureSettingsDraft(); render(); toast("已开启指纹解锁");
                    } catch (Exception error) { deviceUnlock.disableBiometric(); toast("无法保存指纹凭据"); }
                });
            } catch (Exception error) { toast("无法启用指纹解锁：" + error.getMessage()); }
        });
    }

    private void settingsDialog() {
        settingsOpen = true; aboutOpen = false; settingsScrollY = 0; settingsDraft = null; render();
    }

    private void captureSettingsDraft() {
        if (settingsUrl == null || settingsUser == null || settingsPass == null || settingsPath == null
                || settingsLock == null || settingsClip == null || settingsClearPass == null) return;
        SettingsDraft draft = new SettingsDraft();
        draft.url = settingsUrl.getText().toString(); draft.user = settingsUser.getText().toString();
        draft.pass = settingsPass.getText().toString(); draft.path = settingsPath.getText().toString();
        draft.lock = settingsLock.getText().toString(); draft.clip = settingsClip.getText().toString();
        draft.clearPass = settingsClearPass.isChecked(); settingsDraft = draft;
    }

    private void renderSettingsPage() {
        LinearLayout header = row();
        icon(header, "back", "返回验证码", false, () -> { settingsOpen = false; settingsDraft = null; render(); }, 40);
        LinearLayout headings = column(); headings.setPadding(dp(6), 0, 0, 0);
        add(headings, text("设置", 25, true)); add(headings, muted("同步、安全与外观", 12));
        equal(header, headings);
        icon(header, "lock", "锁定保险库", true, () -> { store.lock(); settingsOpen = false; settingsDraft = null; render(); }, 40);
        add(root, header); gap(root, 12);

        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout form = column(); form.setPadding(0, 0, 0, dp(24)); scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll.post(() -> scroll.scrollTo(0, settingsScrollY));

        add(form, sectionTitle("外观"));
        LinearLayout appearance = sectionCard(form);
        add(appearance, text("主题", 15, true)); gap(appearance, 10);
        LinearLayout themeOptions = row(); themeOptions.setPadding(dp(3), dp(3), dp(3), dp(3));
        themeOptions.setBackground(UiStyle.rounded(style.fill, this, 9));
        Button light = button("浅色", () -> setDarkTheme(false));
        Button dark = button("深色", () -> setDarkTheme(true));
        light.setBackground(UiStyle.rounded(style.dark ? style.fill : style.surface, this, 7));
        dark.setBackground(UiStyle.rounded(style.dark ? style.surface : style.fill, this, 7));
        light.setTextColor(style.dark ? style.muted : style.text);
        dark.setTextColor(style.dark ? style.text : style.muted);
        equal(themeOptions, light); equal(themeOptions, dark); add(appearance, themeOptions);
        gap(appearance, 17);
        CheckBox showCodes = new CheckBox(this); showCodes.setText("默认显示所有验证码");
        showCodes.setTextSize(15); showCodes.setTextColor(style.text);
        showCodes.setChecked(showCodesByDefault);
        showCodes.setButtonTintList(android.content.res.ColorStateList.valueOf(style.accent));
        showCodes.setOnCheckedChangeListener((button, checked) -> {
            showCodesByDefault = checked;
            if (!checked) revealed.clear();
            getPreferences(MODE_PRIVATE).edit().putBoolean("show_codes_by_default", checked).apply();
        });
        add(appearance, showCodes);
        add(appearance, muted("开启后解锁进入首页即可查看全部动态验证码；此设置只作用于本机。", 12));

        JSONObject current = store.settings(); SettingsDraft draft = settingsDraft;
        add(form, sectionTitle("常规"));
        LinearLayout general = sectionCard(form);
        EditText lock = labeledField(general, "自动锁定（秒）", "180", draft == null ? String.valueOf(current.optLong("autolock_seconds", 180)) : draft.lock, false);
        EditText clip = labeledField(general, "清空剪贴板（秒）", "30", draft == null ? String.valueOf(current.optLong("clipboard_clear_seconds", 30)) : draft.clip, false);
        lock.setInputType(InputType.TYPE_CLASS_NUMBER); clip.setInputType(InputType.TYPE_CLASS_NUMBER);
        add(general, muted("填 0 表示关闭对应功能。", 12));

        add(form, sectionTitle("解锁方式"));
        LinearLayout security = sectionCard(form);
        add(security, text("指纹解锁", 15, true)); gap(security, 5);
        add(security, muted(deviceUnlock.biometricEnabled()
                ? "已开启 · 锁定后可用指纹解锁"
                : "使用设备指纹解锁保险库", 12)); gap(security, 9);
        add(security, button(deviceUnlock.biometricEnabled() ? "关闭指纹解锁" : "开启指纹解锁", this::toggleBiometricUnlock));
        gap(security, 18);
        add(security, text("免密码进入", 15, true)); gap(security, 5);
        add(security, muted(deviceUnlock.directEnabled()
                ? "已开启 · 打开应用时直接进入，自动锁定暂停"
                : "打开应用时直接进入，无需输入主密码", 12)); gap(security, 9);
        add(security, button(deviceUnlock.directEnabled() ? "关闭免密码进入" : "开启免密码进入", this::toggleDirectUnlock));
        gap(security, 8);
        add(security, muted("开启前优先验证已启用的指纹，也可使用主密码。凭据只保存在这台设备的 Android 密钥库中。", 12));

        add(form, sectionTitle("WEBDAV 同步"));
        LinearLayout sync = sectionCard(form);
        boolean configured = !current.optString("webdav_url").trim().isEmpty();
        TextView status = text(configured ? "●  已配置 · 新账号自动上传" : "●  尚未配置", 13, true);
        status.setTextColor(configured ? style.ok : style.muted); add(sync, status); gap(sync, 13);
        EditText url = labeledField(sync, "服务器地址", "https://dav.example.com/", draft == null ? current.optString("webdav_url") : draft.url, false);
        EditText user = labeledField(sync, "用户名", "WebDAV 用户名", draft == null ? current.optString("webdav_user") : draft.user, false);
        EditText pass = labeledField(sync, "应用密码", "留空保持已保存的密码", draft == null ? "" : draft.pass, true);
        EditText path = labeledField(sync, "远程文件", "/authenticator/vault.enc", draft == null ? current.optString("webdav_path", "/authenticator/vault.enc") : draft.path, false);
        CheckBox clearPass = new CheckBox(this); clearPass.setText("清除已保存的 WebDAV 密码");
        clearPass.setTextSize(13); clearPass.setTextColor(style.text);
        clearPass.setChecked(draft != null && draft.clearPass);
        clearPass.setButtonTintList(android.content.res.ColorStateList.valueOf(style.accent)); add(sync, clearPass);
        settingsUrl = url; settingsUser = user; settingsPass = pass; settingsPath = path;
        settingsLock = lock; settingsClip = clip; settingsClearPass = clearPass;
        gap(sync, 10);
        LinearLayout syncActions = row();
        Button down = button("拉取远程", () -> dialog()
                .setTitle("拉取远程保险库？")
                .setMessage("远程账号将替换本地账号。操作前会保存一份本地加密备份。")
                .setNegativeButton("取消", null)
                .setPositiveButton("拉取", (dialog, which) -> saveSettings(url, user, pass, path, lock, clip, clearPass, false)).show());
        Button up = button("上传本地", () -> saveSettings(url, user, pass, path, lock, clip, clearPass, true));
        equal(syncActions, down);
        View space = new View(this); syncActions.addView(space, new LinearLayout.LayoutParams(dp(9), 1));
        equal(syncActions, up); add(sync, syncActions); gap(sync, 8);
        add(sync, muted("上传会检查远程版本；如有冲突，请先确认各端改动。", 12));

        add(form, sectionTitle("主密码"));
        LinearLayout passwordCard = sectionCard(form);
        EditText next = labeledField(passwordCard, "新密码", "至少 8 位", "", true);
        EditText confirm = labeledField(passwordCard, "确认新密码", "再次输入新密码", "", true);
        add(passwordCard, button("更新主密码", () -> {
            String newValue = next.getText().toString();
            if (!newValue.equals(confirm.getText().toString())) { confirm.setError("两次密码不一致"); return; }
            if (newValue.codePointCount(0, newValue.length()) < 8) { next.setError("新密码至少 8 位"); return; }
            verifyOwner("更新主密码", oldPassword -> run(() -> {
                store.changePassword(oldPassword, newValue); deviceUnlock.clear();
            }, () -> {
                next.setText(""); confirm.setText("");
                captureSettingsDraft(); render();
                toast("主密码已更新；请重新同步其他设备，并按需重新开启快捷解锁");
            }));
        }));
        gap(passwordCard, 8); add(passwordCard, muted("更新时优先验证已启用的指纹，也可使用当前主密码。修改后需要让其他设备使用新密码重新同步。", 12));

        add(form, sectionTitle("关于"));
        LinearLayout aboutCard = sectionCard(form);
        add(aboutCard, text("关于项目", 15, true)); gap(aboutCard, 5);
        add(aboutCard, muted("版本、检查更新、项目地址与开源许可", 12)); gap(aboutCard, 10);
        add(aboutCard, button("查看关于项目", () -> {
            settingsScrollY = scroll.getScrollY(); captureSettingsDraft(); aboutOpen = true; render();
        }));

        gap(root, 10);
        add(root, primaryButton("保存设置", () -> saveSettings(url, user, pass, path, lock, clip, clearPass, null)));
    }

    private void renderAboutPage() {
        LinearLayout header = row();
        icon(header, "back", "返回设置", false, () -> { aboutOpen = false; render(); }, 40);
        LinearLayout headings = column(); headings.setPadding(dp(6), 0, 0, 0);
        add(headings, text("关于项目", 25, true)); add(headings, muted("Open Authenticator", 12));
        equal(header, headings); add(root, header); gap(root, 12);

        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout form = column(); form.setPadding(0, 0, 0, dp(24)); scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        add(form, sectionTitle("应用信息"));
        LinearLayout app = sectionCard(form);
        add(app, text("验证器", 18, true)); gap(app, 7);
        add(app, muted("当前版本  v" + BuildConfig.VERSION_NAME, 13));

        add(form, sectionTitle("更新"));
        LinearLayout updates = sectionCard(form);
        add(updates, text("检查更新", 15, true)); gap(updates, 6);
        add(updates, muted(updateMessage.isEmpty() ? "从 GitHub Releases 检查最新正式版本" : updateMessage, 12));
        gap(updates, 10);
        Button check = button(updateChecking ? "正在检查…" : "检查更新", this::checkForUpdates);
        check.setEnabled(!updateChecking); add(updates, check);
        if (updateResult != null && updateResult.newer) {
            gap(updates, 9);
            add(updates, primaryButton("查看 " + updateResult.latestVersion + " Release", () -> openWeb(updateResult.releaseUrl)));
        }

        add(form, sectionTitle("项目地址"));
        LinearLayout project = sectionCard(form);
        add(project, muted(UpdateChecker.PROJECT_URL, 13)); gap(project, 10);
        add(project, button("打开 GitHub 项目", () -> openWeb(UpdateChecker.PROJECT_URL)));

        add(form, sectionTitle("开源许可"));
        LinearLayout license = sectionCard(form);
        add(license, text("MIT License", 15, true)); gap(license, 5);
        add(license, muted("Copyright © 2026 Xunzi229", 12)); gap(license, 10);
        add(license, button("查看 MIT 许可全文", () -> openWeb("https://opensource.org/license/mit/")));
    }

    private void checkForUpdates() {
        if (updateChecking) return;
        updateChecking = true; updateMessage = "正在联系 GitHub…"; updateResult = null; render();
        worker.submit(() -> {
            try {
                UpdateChecker.Result found = UpdateChecker.check(BuildConfig.VERSION_NAME);
                runOnUiThread(() -> {
                    updateResult = found; updateChecking = false;
                    updateMessage = found.newer ? "发现新版本 " + found.latestVersion
                            : "已是最新版本（" + found.latestVersion + "）";
                    if (aboutOpen) render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    updateChecking = false;
                    updateMessage = "检查失败：" + (error.getMessage() == null ? "请检查网络" : error.getMessage());
                    if (aboutOpen) render();
                });
            }
        });
    }

    private void openWeb(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception error) { toast("无法打开浏览器"); }
    }

    private void setDarkTheme(boolean dark) {
        if (style.dark == dark) return;
        if (settingsOpen) captureSettingsDraft();
        getPreferences(MODE_PRIVATE).edit().putBoolean("dark_theme", dark).apply();
        style = new UiStyle(dark); render();
    }

    private void saveSettings(EditText url, EditText user, EditText pass, EditText path, EditText lock, EditText clip, CheckBox clearPass, Boolean upload) {
        String newUrl = url.getText().toString().trim(), newUser = user.getText().toString().trim(), newPath = path.getText().toString().trim();
        String newPass = pass.getText().toString(), lockValue = lock.getText().toString(), clipValue = clip.getText().toString();
        boolean clearPassword = clearPass.isChecked();
        run(() -> {
            JSONObject settings = new JSONObject(store.settings().toString());
            if (!settings.optString("webdav_url").equals(newUrl) || !settings.optString("webdav_user").equals(newUser) || !settings.optString("webdav_path").equals(newPath)) settings.put("webdav_etag", "");
            settings.put("webdav_url", newUrl).put("webdav_user", newUser).put("webdav_path", newPath.isEmpty() ? "/authenticator/vault.enc" : newPath)
                    .put("autolock_seconds", Long.parseLong(lockValue)).put("clipboard_clear_seconds", Long.parseLong(clipValue));
            if (clearPassword) settings.put("webdav_password", "");
            else if (!newPass.isEmpty()) settings.put("webdav_password", newPass);
            store.updateSettings(settings);
            if (upload != null) {
                if (upload) { String etag = WebDav.put(settings, store.encryptedBytes()); store.setEtag(etag); }
                else { WebDav.Download download = WebDav.get(newUrl, settings.optString("webdav_path"), newUser, settings.optString("webdav_password")); store.replaceRemote(download.bytes, download.etag); }
            }
        }, () -> { settingsDraft = null; render(); toast(upload == null ? "设置已保存" : upload ? "上传成功" : "拉取成功，已保留本地加密备份"); });
    }

    private void restoreDialog() {
        LinearLayout form = column(); form.setPadding(dp(18), dp(8), dp(18), dp(8));
        EditText url = labeledField(form, "WebDAV 地址", "https://dav.example.com/", "", false);
        EditText user = labeledField(form, "用户名", "WebDAV 用户名", "", false);
        EditText pass = labeledField(form, "应用密码", "WebDAV 密码", "", true);
        EditText path = labeledField(form, "远程文件", "/authenticator/vault.enc", "/authenticator/vault.enc", false);
        EditText master = labeledField(form, "保险库主密码", "与桌面端相同的主密码", "", true);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        dialog().setTitle("恢复已有保险库").setView(scroll).setNegativeButton("取消", null)
                .setPositiveButton("拉取", (d, w) -> {
                    String base = url.getText().toString().trim(), remotePath = path.getText().toString().trim();
                    String username = user.getText().toString(), password = pass.getText().toString(), masterPassword = master.getText().toString();
                    run(() -> {
                        WebDav.Download result = WebDav.get(base, remotePath, username, password);
                        store.restoreFirst(result.bytes, masterPassword, base, username, password, remotePath, result.etag);
                    }, this::render);
                }).show();
    }

}
