package app.openauthenticator.android;

import android.app.Activity;
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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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

public final class MainActivity extends Activity {
    private static final int PICK_IMAGE = 1, PICK_TEXT = 2, SAVE_TEXT = 3;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VaultStore store;
    private LinearLayout root, body;
    private EditText search;
    private UiStyle style;
    private UiStyle.CountdownRing countdown;
    private final List<TextView> codeViews = new ArrayList<>();
    private final Set<String> revealed = new HashSet<>();
    private boolean settingsOpen;
    private String query = "";
    private String exportText;
    private String lastCopied;
    private boolean foreground;

    interface Task { void run() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new VaultStore(this);
        style = new UiStyle(getPreferences(MODE_PRIVATE).getBoolean("dark_theme", false));
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        setContentView(root);
        render();
        handler.postDelayed(this::tick, 1000);
    }

    @Override protected void onResume() { super.onResume(); foreground = true; render(); handleIntent(getIntent()); }
    @Override protected void onPause() { foreground = false; super.onPause(); }
    @Override protected void onDestroy() { store.lock(); worker.shutdownNow(); super.onDestroy(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && store != null && store.unlocked()) store.touch();
        return super.dispatchTouchEvent(event);
    }
    @Override public void onBackPressed() {
        if (settingsOpen) { settingsOpen = false; render(); }
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
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, style.dark
                ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert);
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
            public void onTextChanged(CharSequence s, int start, int before, int count) { if (store != null && store.unlocked()) store.touch(); }
            public void afterTextChanged(android.text.Editable value) {}
        });
        return field;
    }
    private EditText multiline(String hint, String value) {
        EditText field = input(hint, value, false); field.setSingleLine(false); field.setMinLines(3); return field;
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
        root.removeAllViews();
        root.setPadding(dp(16), dp(14), dp(16), dp(12)); applyWindowColors();
        codeViews.clear(); countdown = null; body = null;
        if (!store.exists()) { settingsOpen = false; authScreen(true); return; }
        if (!store.unlocked()) { settingsOpen = false; revealed.clear(); authScreen(false); return; }
        try { store.checkLock(); } catch (Exception error) { settingsOpen = false; revealed.clear(); authScreen(false); return; }
        if (settingsOpen) { renderSettingsPage(); return; }
        renderHome();
    }

    private void renderHome() {
        LinearLayout header = row();
        LinearLayout titleBlock = column();
        add(titleBlock, text("验证码", 28, true));
        TextView subtitle = muted("点验证码显示 · 复制验证码或邮箱", 12);
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
        icon(toolbar, "scan", "扫码添加", true, this::scan, 40);
        icon(toolbar, "plus", "手动添加", true, () -> editAccount(null), 40);
        icon(toolbar, "upload", "导入导出", true, this::menu, 40);
        icon(toolbar, "settings", "设置", true, this::settingsDialog, 40);
        add(root, toolbar); gap(root, 12);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        body = column(); body.setPadding(0, 0, 0, dp(24)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
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
                TextView hint = muted(hasAccounts ? "换个关键词试试" : "扫码或手动添加 TOTP 账号", 13);
                hint.setGravity(Gravity.CENTER); add(empty, hint);
                gap(empty, 18);
                add(empty, primaryButton(hasAccounts ? "清除搜索" : "扫码添加", hasAccounts ? () -> search.setText("") : this::scan));
            }
        } catch (Exception error) { toast("无法显示账号"); }
    }

    private LinearLayout accountRow(JSONObject a) {
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
        TextView code = text(a.optInt("digits") == 8 ? "•••• ••••" : "••• •••", 18, true);
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); code.setTextColor(style.hidden);
        code.setGravity(Gravity.CENTER); code.setPadding(dp(4), dp(6), dp(2), dp(6)); code.setTag(a);
        code.setContentDescription("点按显示验证码");
        code.setOnClickListener(v -> {
            if (revealed.add(a.optString("id"))) showCode(code, a);
            else copyCode(a);
        });
        if (revealed.contains(a.optString("id"))) showCode(code, a);
        top.addView(code, new LinearLayout.LayoutParams(-2, -2)); codeViews.add(code);
        add(item, top);
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
        icon(actions, "edit", "编辑账号", false, () -> editAccount(a), 30);
        icon(actions, "delete", "删除账号", false, () -> confirmDelete(a), 30);
        add(item, actions);
        return item;
    }

    private int emailColor(String email) {
        int[] colors = {0xff1d4ed8, 0xffc2410c, 0xff15803d, 0xffbe123c, 0xff7e22ce,
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
                    if (account instanceof JSONObject && revealed.contains(((JSONObject) account).optString("id"))) {
                        showCode(code, (JSONObject) account);
                    }
                }
            } catch (Exception error) { render(); }
        }
        handler.postDelayed(this::tick, 1000);
    }

    private void editAccount(JSONObject existing) {
        LinearLayout form = column(); form.setPadding(dp(18), dp(8), dp(18), dp(8));
        EditText issuer = labeledField(form, "发行方", "例如 OpenAI", existing == null ? "" : existing.optString("issuer"), false);
        EditText name = labeledField(form, "名称或账号", "账号名称", existing == null ? "" : existing.optString("name"), false);
        EditText email = labeledField(form, "邮箱", "可选", existing == null ? "" : existing.optString("email"), false);
        EditText notes = labeledField(form, "备注", "可选", existing == null ? "" : existing.optString("notes"), false);
        EditText secret = labeledField(form, "Base32 密钥", "粘贴密钥", existing == null ? "" : existing.optString("secret"), false);
        EditText algorithm = labeledField(form, "算法", "SHA1 / SHA256 / SHA512", existing == null ? "SHA1" : existing.optString("algorithm"), false);
        EditText digits = labeledField(form, "验证码位数", "6 或 8", existing == null ? "6" : String.valueOf(existing.optInt("digits")), false);
        EditText period = labeledField(form, "周期（秒）", "30", existing == null ? "30" : String.valueOf(existing.optLong("period")), false);
        digits.setInputType(InputType.TYPE_CLASS_NUMBER); period.setInputType(InputType.TYPE_CLASS_NUMBER);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        dialog().setTitle(existing == null ? "添加账号" : "编辑账号").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                    String issuerValue = issuer.getText().toString(), nameValue = name.getText().toString();
                    String emailValue = email.getText().toString(), notesValue = notes.getText().toString();
                    String secretValue = secret.getText().toString(), algorithmValue = algorithm.getText().toString();
                    String digitsValue = digits.getText().toString(), periodValue = period.getText().toString();
                    String[] message = new String[1];
                    run(() -> {
                    JSONObject a = existing == null ? new JSONObject() : new JSONObject(existing.toString());
                    a.put("issuer", issuerValue).put("name", nameValue).put("email", emailValue)
                            .put("notes", notesValue).put("secret", secretValue)
                            .put("algorithm", algorithmValue).put("digits", Integer.parseInt(digitsValue))
                            .put("period", Long.parseLong(periodValue));
                    store.upsert(a);
                    message[0] = existing == null ? autoUploadNewAccounts() : "账号已保存；编辑后的内容可在设置中上传";
                    }, () -> { render(); showSaveResult(message[0]); });
                }).show();
    }

    private void confirmDelete(JSONObject a) {
        dialog().setTitle("删除账号？").setMessage(a.optString("issuer") + " " + a.optString("name"))
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> run(() -> store.delete(a.getString("id")), this::render)).show();
    }

    private void menu() {
        String[] items = {"扫描二维码", "从图片导入", "粘贴链接或文本", "从文件导入", "导出备份", "设置与同步", "锁定"};
        dialog().setItems(items, (dialog, index) -> {
            switch (index) {
                case 0: scan(); break;
                case 1: pick("image/*", PICK_IMAGE); break;
                case 2: importDialog(); break;
                case 3: pick("*/*", PICK_TEXT); break;
                case 4: exportDialog(); break;
                case 5: settingsDialog(); break;
                case 6: store.lock(); render(); break;
            }
        }).show();
    }

    private void scan() {
        new IntentIntegrator(this).setPrompt("扫描验证器二维码").setBeepEnabled(false).initiateScan();
    }

    private void importDialog() {
        EditText content = multiline("otpauth:// 链接、Google 转移链接或 JSON", "");
        dialog().setTitle("导入账号").setView(content).setNegativeButton("取消", null)
                .setPositiveButton("导入", (d, w) -> importContent(content.getText().toString())).show();
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
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == SAVE_TEXT) {
            String value = exportText; exportText = null;
            run(() -> { try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) { out.write(value.getBytes(StandardCharsets.UTF_8)); } }, () -> toast("已保存"));
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
            BitMatrix matrix = new QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 720, 720);
            int[] pixels = new int[720 * 720]; for (int y = 0; y < 720; y++) for (int x = 0; x < 720; x++) pixels[y * 720 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            Bitmap bitmap = Bitmap.createBitmap(pixels, 720, 720, Bitmap.Config.ARGB_8888);
            ImageView image = new ImageView(this); image.setImageBitmap(bitmap); image.setPadding(dp(20), dp(20), dp(20), dp(20));
            dialog().setTitle("账号二维码").setView(image).setNegativeButton("关闭", null)
                    .setPositiveButton("复制链接", (d, w) -> copy(uri, "账号链接", 30)).show();
        } catch (Exception error) { toast(error.getMessage()); }
    }

    private void exportDialog() {
        EditText password = input("主密码", "", true);
        dialog().setTitle("导出备份").setMessage("导出内容包含明文密钥，请妥善保管。")
                .setView(password).setNegativeButton("取消", null).setPositiveButton("继续", (d, w) -> {
                    String candidate = password.getText().toString();
                    run(() -> {
                    try { VaultCodec.decrypt(store.encryptedBytes(), candidate); }
                    catch (Exception error) { throw new IllegalArgumentException("密码错误"); }
                    JSONArray accounts = store.accounts(); JSONArray result = new JSONArray();
                    for (int i = 0; i < accounts.length(); i++) {
                        JSONObject a = accounts.getJSONObject(i);
                        result.put(new JSONObject().put("issuer", a.getString("issuer")).put("name", a.getString("name"))
                                .put("secret", a.getString("secret")).put("algorithm", a.getString("algorithm"))
                                .put("digits", a.getInt("digits")).put("period", a.getLong("period"))
                                .put("uri", OtpUri.export(a)));
                    }
                    exportText = result.toString(2);
                }, () -> {
                    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT); save.addCategory(Intent.CATEGORY_OPENABLE);
                    save.setType("application/json"); save.putExtra(Intent.EXTRA_TITLE, "authenticator-backup.json"); startActivityForResult(save, SAVE_TEXT);
                    });
                }).show();
    }

    private void copy(String value, String label, long clearSeconds) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value)); lastCopied = value;
        if (clearSeconds > 0) handler.postDelayed(() -> {
            try { if (value.equals(lastCopied) && clipboard.hasPrimaryClip() && value.equals(clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString())) clipboard.clearPrimaryClip(); }
            catch (Exception ignored) {} }, Math.min(clearSeconds, 86400) * 1000);
    }

    private void settingsDialog() {
        settingsOpen = true; render();
    }

    private void renderSettingsPage() {
        LinearLayout header = row();
        icon(header, "back", "返回验证码", false, () -> { settingsOpen = false; render(); }, 40);
        LinearLayout headings = column(); headings.setPadding(dp(6), 0, 0, 0);
        add(headings, text("设置", 25, true)); add(headings, muted("同步、安全与外观", 12));
        equal(header, headings);
        icon(header, "lock", "锁定保险库", true, () -> { store.lock(); settingsOpen = false; render(); }, 40);
        add(root, header); gap(root, 12);

        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout form = column(); form.setPadding(0, 0, 0, dp(24)); scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

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

        JSONObject current = store.settings();
        add(form, sectionTitle("常规"));
        LinearLayout general = sectionCard(form);
        EditText lock = labeledField(general, "自动锁定（秒）", "180", String.valueOf(current.optLong("autolock_seconds", 180)), false);
        EditText clip = labeledField(general, "清空剪贴板（秒）", "30", String.valueOf(current.optLong("clipboard_clear_seconds", 30)), false);
        lock.setInputType(InputType.TYPE_CLASS_NUMBER); clip.setInputType(InputType.TYPE_CLASS_NUMBER);
        add(general, muted("填 0 表示关闭对应功能。", 12));

        add(form, sectionTitle("WEBDAV 同步"));
        LinearLayout sync = sectionCard(form);
        boolean configured = !current.optString("webdav_url").trim().isEmpty();
        TextView status = text(configured ? "●  已配置 · 新账号自动上传" : "●  尚未配置", 13, true);
        status.setTextColor(configured ? style.ok : style.muted); add(sync, status); gap(sync, 13);
        EditText url = labeledField(sync, "服务器地址", "https://dav.example.com/", current.optString("webdav_url"), false);
        EditText user = labeledField(sync, "用户名", "WebDAV 用户名", current.optString("webdav_user"), false);
        EditText pass = labeledField(sync, "应用密码", "留空保持已保存的密码", "", true);
        EditText path = labeledField(sync, "远程文件", "/authenticator/vault.enc", current.optString("webdav_path", "/authenticator/vault.enc"), false);
        CheckBox clearPass = new CheckBox(this); clearPass.setText("清除已保存的 WebDAV 密码");
        clearPass.setTextSize(13); clearPass.setTextColor(style.text);
        clearPass.setButtonTintList(android.content.res.ColorStateList.valueOf(style.accent)); add(sync, clearPass);
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
        EditText old = labeledField(passwordCard, "当前密码", "当前主密码", "", true);
        EditText next = labeledField(passwordCard, "新密码", "至少 8 位", "", true);
        EditText confirm = labeledField(passwordCard, "确认新密码", "再次输入新密码", "", true);
        add(passwordCard, button("更新主密码", () -> {
            String oldValue = old.getText().toString(), newValue = next.getText().toString();
            if (!newValue.equals(confirm.getText().toString())) { toast("两次密码不一致"); return; }
            run(() -> store.changePassword(oldValue, newValue), () -> {
                old.setText(""); next.setText(""); confirm.setText("");
                toast("主密码已更新，请重新同步其他设备");
            });
        }));
        gap(passwordCard, 8); add(passwordCard, muted("修改后需要让其他设备使用新密码重新同步。", 12));

        gap(root, 10);
        add(root, primaryButton("保存设置", () -> saveSettings(url, user, pass, path, lock, clip, clearPass, null)));
    }

    private void setDarkTheme(boolean dark) {
        if (style.dark == dark) return;
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
        }, () -> { render(); toast(upload == null ? "设置已保存" : upload ? "上传成功" : "拉取成功，已保留本地加密备份"); });
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
