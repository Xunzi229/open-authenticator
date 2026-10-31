package app.openauthenticator.android;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;

final class VaultStore {
    private final File file;
    private final android.content.SharedPreferences deviceUnlock;
    private JSONObject payload;
    private String password;
    private long activeAt;
    private int failures;

    VaultStore(Context context) {
        file = new File(context.getFilesDir(), "vault.enc");
        deviceUnlock = context.getSharedPreferences("device_unlock", Context.MODE_PRIVATE);
    }
    boolean exists() { return file.isFile() && file.length() > 0; }
    boolean unlocked() { return payload != null && password != null; }
    void lock() { payload = null; password = null; }
    void touch() { activeAt = System.currentTimeMillis(); }

    void checkLock() {
        if (!unlocked()) throw new IllegalStateException("保险库已锁定");
        checkTimeout();
        touch();
    }

    void checkTimeout() {
        if (!unlocked()) throw new IllegalStateException("保险库已锁定");
        long seconds = settings().optLong("autolock_seconds", 180);
        if (!deviceUnlock.contains("direct") && seconds > 0 && System.currentTimeMillis() - activeAt > seconds * 1000) {
            lock(); throw new IllegalStateException("保险库已自动锁定");
        }
    }

    void setup(String newPassword) throws Exception {
        if (exists()) throw new IllegalStateException("保险库已存在");
        if (newPassword.codePointCount(0, newPassword.length()) < 8) throw new IllegalArgumentException("主密码至少 8 位");
        JSONObject initial = VaultCodec.empty();
        save(initial, newPassword);
        payload = initial; password = newPassword; touch();
    }

    void unlock(String candidate) throws Exception {
        if (!exists()) throw new IllegalStateException("还没有保险库");
        if (file.length() > VaultCodec.LIMIT) throw new IllegalArgumentException("保险库文件过大");
        if (failures > 0) Thread.sleep(Math.min(4000, 350L << Math.min(failures, 4)));
        try {
            JSONObject decoded = VaultCodec.decrypt(Files.readAllBytes(file.toPath()), candidate);
            payload = decoded; password = candidate; failures = 0; touch();
        } catch (Exception error) { failures++; throw error; }
    }

    void verifyPassword(String candidate) throws Exception {
        if (!exists()) throw new IllegalStateException("还没有保险库");
        try { VaultCodec.decrypt(Files.readAllBytes(file.toPath()), candidate); }
        catch (Exception error) { throw new IllegalArgumentException("当前主密码错误"); }
    }

    JSONObject data() { checkLock(); return payload; }
    JSONObject settings() { return payload.optJSONObject("settings") == null ? new JSONObject() : payload.optJSONObject("settings"); }
    JSONArray accounts() { return data().optJSONArray("accounts"); }
    byte[] encryptedBytes() throws Exception { checkLock(); if (file.length() > VaultCodec.LIMIT) throw new IllegalArgumentException("保险库文件过大"); return Files.readAllBytes(file.toPath()); }

    void updateSettings(JSONObject settings) throws Exception {
        checkLock();
        JSONObject next = new JSONObject(payload.toString());
        next.put("settings", settings);
        commit(next);
    }

    void upsert(JSONObject account) throws Exception {
        checkLock();
        JSONObject next = new JSONObject(payload.toString());
        JSONArray accounts = next.getJSONArray("accounts");
        normalizeAccount(account);
        String id = account.optString("id");
        int index = -1; for (int i = 0; i < accounts.length(); i++) if (accounts.getJSONObject(i).getString("id").equals(id)) index = i;
        if (index >= 0) accounts.put(index, account); else accounts.put(account);
        commit(next);
    }

    private void normalizeAccount(JSONObject account) throws Exception {
        String id = account.optString("id");
        if (id.isEmpty()) {
            byte[] random = new byte[16]; new SecureRandom().nextBytes(random);
            StringBuilder hex = new StringBuilder(); for (byte b : random) hex.append(String.format("%02x", b & 255));
            id = hex.toString(); account.put("id", id);
        }
        String secret = Totp.normalize(account.getString("secret"));
        String algorithm = account.getString("algorithm").toUpperCase(java.util.Locale.ROOT).replace("-", "");
        int digits = account.getInt("digits"); long period = account.getLong("period");
        Totp.validate(secret, algorithm, digits, period);
        account.put("secret", secret).put("algorithm", algorithm);
        for (String field : new String[]{"issuer", "name", "email", "notes"}) account.put(field, account.optString(field).trim());
        if (account.optString("issuer").isEmpty() && account.optString("name").isEmpty()) throw new IllegalArgumentException("请填写名称或发行方");
        long now = System.currentTimeMillis() / 1000;
        account.put("created", account.optLong("created") == 0 ? now : account.optLong("created")).put("updated", now);
    }

    int importAccounts(java.util.List<JSONObject> imported) throws Exception {
        checkLock();
        JSONObject next = new JSONObject(payload.toString());
        JSONArray current = next.getJSONArray("accounts");
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < current.length(); i++) seen.add(duplicateKey(current.getJSONObject(i)));
        int added = 0;
        for (JSONObject item : imported) {
            normalizeAccount(item);
            if (seen.add(duplicateKey(item))) { current.put(item); added++; }
        }
        if (added > 0) commit(next);
        return added;
    }

    private String duplicateKey(JSONObject a) {
        return a.optString("issuer").toLowerCase(java.util.Locale.ROOT) + "\u0000" + a.optString("secret") + "\u0000"
                + a.optString("algorithm") + "\u0000" + a.optInt("digits") + "\u0000" + a.optLong("period");
    }

    void delete(String id) throws Exception {
        checkLock(); JSONObject next = new JSONObject(payload.toString()); JSONArray old = next.getJSONArray("accounts"), fresh = new JSONArray();
        for (int i = 0; i < old.length(); i++) if (!old.getJSONObject(i).getString("id").equals(id)) fresh.put(old.getJSONObject(i));
        if (fresh.length() == old.length()) throw new IllegalArgumentException("账号不存在");
        next.put("accounts", fresh); commit(next);
    }

    void changePassword(String oldPassword, String newPassword) throws Exception {
        checkLock();
        if (!password.equals(oldPassword)) throw new IllegalArgumentException("当前密码错误");
        if (newPassword.codePointCount(0, newPassword.length()) < 8) throw new IllegalArgumentException("新密码至少 8 位");
        save(payload, newPassword); password = newPassword; touch();
    }

    void replaceRemote(byte[] blob, String etag) throws Exception {
        checkLock();
        JSONObject remote = VaultCodec.decrypt(blob, password);
        JSONObject current = settings(), received = remote.optJSONObject("settings");
        if (received == null) received = new JSONObject();
        for (String field : new String[]{"webdav_url", "webdav_user", "webdav_password", "webdav_path"}) received.put(field, current.optString(field));
        received.put("webdav_etag", etag);
        remote.put("settings", received);
        backup(); save(remote, password); payload = remote; touch();
    }

    void restoreFirst(byte[] blob, String masterPassword, String url, String user, String pass, String path, String etag) throws Exception {
        if (exists()) throw new IllegalStateException("本地保险库已存在");
        JSONObject remote = VaultCodec.decrypt(blob, masterPassword);
        JSONObject settings = remote.optJSONObject("settings"); if (settings == null) settings = new JSONObject();
        settings.put("webdav_url", url).put("webdav_user", user).put("webdav_password", pass)
                .put("webdav_path", path).put("webdav_etag", etag);
        remote.put("settings", settings);
        save(remote, masterPassword); payload = remote; password = masterPassword; touch();
    }

    void setEtag(String etag) throws Exception {
        checkLock(); JSONObject next = new JSONObject(payload.toString()); next.getJSONObject("settings").put("webdav_etag", etag); commit(next);
    }

    private void commit(JSONObject next) throws Exception { VaultCodec.validate(next); save(next, password); payload = next; touch(); }
    private void save(JSONObject next, String pass) throws Exception {
        byte[] blob = VaultCodec.encrypt(next, pass);
        AtomicFile atomic = new AtomicFile(file); FileOutputStream stream = atomic.startWrite();
        try { stream.write(blob); atomic.finishWrite(stream); } catch (Exception error) { atomic.failWrite(stream); throw error; }
        Arrays.fill(blob, (byte) 0);
    }
    private void backup() throws Exception {
        if (!exists()) return;
        File dir = new File(file.getParentFile(), "backups"); if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法建立备份目录");
        Files.copy(file.toPath(), new File(dir, "vault-" + System.currentTimeMillis() + ".enc").toPath());
    }
}
