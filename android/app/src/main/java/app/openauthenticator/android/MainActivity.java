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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_IMAGE = 1, PICK_TEXT = 2, SAVE_TEXT = 3;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VaultStore store;
    private LinearLayout root, body;
    private EditText search;
    private String exportText;
    private String lastCopied;
    private boolean foreground;

    interface Task { void run() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new VaultStore(this);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18), dp(16), dp(18), dp(12)); root.setBackgroundColor(0xfff6f8fa);
        setContentView(root);
        render();
        handler.postDelayed(this::tick, 1000);
    }

    @Override protected void onResume() { super.onResume(); foreground = true; render(); handleIntent(getIntent()); }
    @Override protected void onPause() { foreground = false; super.onPause(); }
    @Override protected void onDestroy() { store.lock(); worker.shutdownNow(); super.onDestroy(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); }

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
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void showSaveResult(String message) {
        if (message.contains("同步失败") || message.contains("同步标记保存失败")) {
            new AlertDialog.Builder(this).setTitle("本地已保存").setMessage(message).setPositiveButton("知道了", null).show();
        } else toast(message);
    }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(0xff172332);
        if (bold) view.setTypeface(null, Typeface.BOLD); view.setPadding(0, dp(6), 0, dp(6)); return view;
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(v -> action.run()); return b;
    }
    private EditText input(String hint, String value, boolean secret) {
        EditText field = new EditText(this); field.setSingleLine(true); field.setText(value); field.setHint(hint); field.setTextSize(16);
        if (secret) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }
    private EditText multiline(String hint, String value) {
        EditText field = input(hint, value, false); field.setSingleLine(false); field.setMinLines(3); return field;
    }
    private LinearLayout column() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); return c; }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    private void add(LinearLayout parent, View child) { parent.addView(child, new LinearLayout.LayoutParams(-1, -2)); }
    private void equal(LinearLayout parent, View child) { parent.addView(child, new LinearLayout.LayoutParams(0, -2, 1)); }

    private void render() {
        root.removeAllViews();
        if (!store.exists()) { authScreen(true); return; }
        if (!store.unlocked()) { authScreen(false); return; }
        try { store.checkLock(); } catch (Exception error) { authScreen(false); return; }
        LinearLayout header = row();
        TextView title = text("验证器", 26, true); equal(header, title);
        header.addView(button("扫码", this::scan));
        header.addView(button("＋", () -> editAccount(null)));
        header.addView(button("⋮", this::menu));
        add(root, header);
        search = input("搜索账号", "", false); search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int before, int count) { fillAccounts(s.toString()); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        add(root, search);
        ScrollView scroll = new ScrollView(this); body = column(); scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        fillAccounts("");
    }

    private void authScreen(boolean setup) {
        add(root, text(setup ? "创建加密保险库" : "解锁验证器", 26, true));
        add(root, text(setup ? "主密码至少 8 位。请妥善保管，无法找回。" : "输入主密码以查看验证码。", 14, false));
        EditText password = input("主密码", "", true); add(root, password);
        EditText confirm = null;
        if (setup) { confirm = input("再次输入主密码", "", true); add(root, confirm); }
        EditText finalConfirm = confirm;
        add(root, button(setup ? "创建" : "解锁", () -> {
            String pass = password.getText().toString();
            if (setup && !pass.equals(finalConfirm.getText().toString())) { toast("两次密码不一致"); return; }
            run(() -> { if (setup) store.setup(pass); else store.unlock(pass); }, () -> { render(); handleIntent(getIntent()); });
        }));
        if (setup) add(root, button("从 WebDAV 恢复", this::restoreDialog));
    }

    private void fillAccounts(String query) {
        if (body == null || !store.unlocked()) return;
        body.removeAllViews();
        try {
            JSONArray accounts = store.accounts(); int shown = 0;
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject a = accounts.getJSONObject(i);
                String label = a.optString("issuer") + " " + a.optString("name") + " " + a.optString("email") + " " + a.optString("notes");
                if (!label.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT).trim())) continue;
                shown++;
                LinearLayout card = column(); card.setPadding(dp(12), dp(8), dp(12), dp(8));
                card.setBackgroundColor(Color.WHITE);
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2); cardParams.setMargins(0, dp(6), 0, dp(6)); body.addView(card, cardParams);
                add(card, text(a.optString("issuer").isEmpty() ? "其他" : a.optString("issuer"), 14, false));
                add(card, text(a.optString("name"), 18, true));
                if (!a.optString("email").isEmpty() && !a.optString("email").equals(a.optString("name")))
                    add(card, text(a.optString("email"), 14, false));
                TextView code = text(a.optInt("digits") == 8 ? "•••• ••••" : "••• •••", 28, true); code.setTag(a); add(card, code);
                code.setOnClickListener(v -> {
                    if (code.getText().toString().contains("•")) showCode(code, a);
                    else {
                        try {
                            String value = Totp.code(a.getString("secret"), a.getString("algorithm"), a.getInt("digits"), a.getLong("period"), System.currentTimeMillis() / 1000);
                            copy(value, "验证码", store.settings().optLong("clipboard_clear_seconds", 30)); toast("已复制验证码");
                        } catch (Exception error) { toast(error.getMessage()); }
                    }
                });
                LinearLayout actions = row(); equal(actions, button("编辑", () -> editAccount(a))); equal(actions, button("二维码", () -> showQr(a)));
                equal(actions, button("删除", () -> confirmDelete(a))); add(card, actions);
            }
            if (shown == 0) {
                add(body, text("暂无账号，可扫码或点击＋添加。", 16, false));
                add(body, button("扫码添加账号", this::scan));
            }
        } catch (Exception error) { toast("无法显示账号"); }
    }

    private void showCode(TextView view, JSONObject a) {
        try {
            long now = System.currentTimeMillis() / 1000;
            String code = Totp.code(a.getString("secret"), a.getString("algorithm"), a.getInt("digits"), a.getLong("period"), now);
            String formatted = code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code.substring(0, 4) + " " + code.substring(4);
            view.setText(formatted + "  ·  " + (a.getLong("period") - now % a.getLong("period")) + "s");
            view.setContentDescription("验证码，点击复制");
        } catch (Exception error) { toast(error.getMessage()); }
    }

    private void tick() {
        if (foreground && store.unlocked()) {
            try {
                store.checkTimeout();
                if (body != null) {
                    for (int i = 0; i < body.getChildCount(); i++) {
                        View card = body.getChildAt(i);
                        if (!(card instanceof ViewGroup)) continue;
                        ViewGroup group = (ViewGroup) card;
                        for (int j = 0; j < group.getChildCount(); j++) {
                            View v = group.getChildAt(j);
                            if (v instanceof TextView && v.getTag() instanceof JSONObject && !((TextView) v).getText().toString().contains("•")) showCode((TextView) v, (JSONObject) v.getTag());
                        }
                    }
                }
            } catch (Exception error) { render(); }
        }
        handler.postDelayed(this::tick, 1000);
    }

    private void editAccount(JSONObject existing) {
        LinearLayout form = column(); form.setPadding(dp(18), 0, dp(18), 0);
        EditText issuer = input("发行方", existing == null ? "" : existing.optString("issuer"), false);
        EditText name = input("名称或账号", existing == null ? "" : existing.optString("name"), false);
        EditText email = input("邮箱", existing == null ? "" : existing.optString("email"), false);
        EditText notes = input("备注", existing == null ? "" : existing.optString("notes"), false);
        EditText secret = input("Base32 密钥", existing == null ? "" : existing.optString("secret"), false);
        EditText algorithm = input("算法 SHA1 / SHA256 / SHA512", existing == null ? "SHA1" : existing.optString("algorithm"), false);
        EditText digits = input("位数 6 / 8", existing == null ? "6" : String.valueOf(existing.optInt("digits")), false);
        EditText period = input("周期（秒）", existing == null ? "30" : String.valueOf(existing.optLong("period")), false);
        for (EditText field : new EditText[]{issuer, name, email, notes, secret, algorithm, digits, period}) add(form, field);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        new AlertDialog.Builder(this).setTitle(existing == null ? "添加账号" : "编辑账号").setView(scroll)
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
        new AlertDialog.Builder(this).setTitle("删除账号？").setMessage(a.optString("issuer") + " " + a.optString("name"))
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> run(() -> store.delete(a.getString("id")), this::render)).show();
    }

    private void menu() {
        String[] items = {"扫描二维码", "从图片导入", "粘贴链接或文本", "从文件导入", "导出备份", "WebDAV 设置与同步", "修改主密码", "锁定"};
        new AlertDialog.Builder(this).setItems(items, (dialog, index) -> {
            switch (index) {
                case 0: scan(); break;
                case 1: pick("image/*", PICK_IMAGE); break;
                case 2: importDialog(); break;
                case 3: pick("*/*", PICK_TEXT); break;
                case 4: exportDialog(); break;
                case 5: settingsDialog(); break;
                case 6: passwordDialog(); break;
                case 7: store.lock(); render(); break;
            }
        }).show();
    }

    private void scan() {
        new IntentIntegrator(this).setPrompt("扫描验证器二维码").setBeepEnabled(false).initiateScan();
    }

    private void importDialog() {
        EditText content = multiline("otpauth:// 链接、Google 转移链接或 JSON", "");
        new AlertDialog.Builder(this).setTitle("导入账号").setView(content).setNegativeButton("取消", null)
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
            new AlertDialog.Builder(this).setTitle("账号二维码").setView(image).setNegativeButton("关闭", null)
                    .setPositiveButton("复制链接", (d, w) -> copy(uri, "账号链接", 30)).show();
        } catch (Exception error) { toast(error.getMessage()); }
    }

    private void exportDialog() {
        EditText password = input("主密码", "", true);
        new AlertDialog.Builder(this).setTitle("导出备份").setMessage("导出内容包含明文密钥，请妥善保管。")
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
        JSONObject current = store.settings(); LinearLayout form = column(); form.setPadding(dp(18), 0, dp(18), 0);
        EditText url = input("WebDAV 地址（HTTPS）", current.optString("webdav_url"), false);
        EditText user = input("用户名", current.optString("webdav_user"), false);
        EditText pass = input("应用密码，留空保持原值", "", true);
        EditText path = input("远程路径", current.optString("webdav_path", "/authenticator/vault.enc"), false);
        EditText lock = input("自动锁定秒数，0 为关闭", String.valueOf(current.optLong("autolock_seconds", 180)), false);
        EditText clip = input("剪贴板清除秒数", String.valueOf(current.optLong("clipboard_clear_seconds", 30)), false);
        for (EditText v : new EditText[]{url, user, pass, path, lock, clip}) add(form, v);
        add(form, button("上传到 WebDAV", () -> saveSettings(url, user, pass, path, lock, clip, true)));
        add(form, button("从 WebDAV 拉取", () -> saveSettings(url, user, pass, path, lock, clip, false)));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("设置与同步").setView(scroll).setNegativeButton("关闭", null)
                .setPositiveButton("保存设置", (d, w) -> saveSettings(url, user, pass, path, lock, clip, null)).show();
    }

    private void saveSettings(EditText url, EditText user, EditText pass, EditText path, EditText lock, EditText clip, Boolean upload) {
        String newUrl = url.getText().toString().trim(), newUser = user.getText().toString().trim(), newPath = path.getText().toString().trim();
        String newPass = pass.getText().toString(), lockValue = lock.getText().toString(), clipValue = clip.getText().toString();
        run(() -> {
            JSONObject settings = new JSONObject(store.settings().toString());
            if (!settings.optString("webdav_url").equals(newUrl) || !settings.optString("webdav_user").equals(newUser) || !settings.optString("webdav_path").equals(newPath)) settings.put("webdav_etag", "");
            settings.put("webdav_url", newUrl).put("webdav_user", newUser).put("webdav_path", newPath.isEmpty() ? "/authenticator/vault.enc" : newPath)
                    .put("autolock_seconds", Long.parseLong(lockValue)).put("clipboard_clear_seconds", Long.parseLong(clipValue));
            if (!newPass.isEmpty()) settings.put("webdav_password", newPass);
            store.updateSettings(settings);
            if (upload != null) {
                if (upload) { String etag = WebDav.put(settings, store.encryptedBytes()); store.setEtag(etag); }
                else { WebDav.Download download = WebDav.get(newUrl, settings.optString("webdav_path"), newUser, settings.optString("webdav_password")); store.replaceRemote(download.bytes, download.etag); }
            }
        }, () -> { render(); toast(upload == null ? "设置已保存" : upload ? "上传成功" : "拉取成功，已保留本地加密备份"); });
    }

    private void restoreDialog() {
        LinearLayout form = column(); form.setPadding(dp(18), 0, dp(18), 0);
        EditText url = input("WebDAV 地址", "", false), user = input("用户名", "", false), pass = input("应用密码", "", true);
        EditText path = input("远程路径", "/authenticator/vault.enc", false), master = input("保险库主密码", "", true);
        for (EditText v : new EditText[]{url, user, pass, path, master}) add(form, v);
        new AlertDialog.Builder(this).setTitle("恢复已有保险库").setView(form).setNegativeButton("取消", null)
                .setPositiveButton("拉取", (d, w) -> {
                    String base = url.getText().toString().trim(), remotePath = path.getText().toString().trim();
                    String username = user.getText().toString(), password = pass.getText().toString(), masterPassword = master.getText().toString();
                    run(() -> {
                        WebDav.Download result = WebDav.get(base, remotePath, username, password);
                        store.restoreFirst(result.bytes, masterPassword, base, username, password, remotePath, result.etag);
                    }, this::render);
                }).show();
    }

    private void passwordDialog() {
        LinearLayout form = column(); form.setPadding(dp(18), 0, dp(18), 0);
        EditText old = input("当前密码", "", true), next = input("新密码", "", true), confirm = input("再次输入新密码", "", true);
        for (EditText field : new EditText[]{old, next, confirm}) add(form, field);
        new AlertDialog.Builder(this).setTitle("修改主密码").setView(form).setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    String oldPassword = old.getText().toString(), newPassword = next.getText().toString();
                    if (!newPassword.equals(confirm.getText().toString())) { toast("两次密码不一致"); return; }
                    run(() -> store.changePassword(oldPassword, newPassword), () -> toast("主密码已更新，请重新同步其他设备"));
                }).show();
    }
}
