package app.openauthenticator.android;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class VaultCodec {
    static final int LIMIT = 16 * 1024 * 1024;
    private static final byte[] MAGIC = {'G', 'A', 'E', '1'};
    private static final SecureRandom RANDOM = new SecureRandom();

    static byte[] encrypt(JSONObject payload, String password) throws Exception {
        byte[] plain = payload.toString().getBytes(StandardCharsets.UTF_8);
        if (plain.length > LIMIT) throw new IllegalArgumentException("保险库数据过大");
        byte[] salt = new byte[16], nonce = new byte[12];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);
        byte[] key = derive(password, salt, 32768, 3, 2);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(MAGIC);
            byte[] sealed = cipher.doFinal(plain);
            return ByteBuffer.allocate(38 + sealed.length).order(ByteOrder.BIG_ENDIAN)
                    .put(MAGIC).put(salt).putInt(32768).put((byte) 3).put((byte) 2).put(nonce).put(sealed).array();
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(plain, (byte) 0);
        }
    }

    static JSONObject decrypt(byte[] blob, String password) throws Exception {
        if (blob.length < 54 || blob.length > LIMIT || !Arrays.equals(Arrays.copyOf(blob, 4), MAGIC))
            throw new IllegalArgumentException("保险库格式无效");
        ByteBuffer input = ByteBuffer.wrap(blob).order(ByteOrder.BIG_ENDIAN);
        input.position(4);
        byte[] salt = new byte[16], nonce = new byte[12];
        input.get(salt);
        int memory = input.getInt(), time = Byte.toUnsignedInt(input.get()), lanes = Byte.toUnsignedInt(input.get());
        input.get(nonce);
        byte[] key = derive(password, salt, memory, time, lanes);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(MAGIC);
            byte[] plain = cipher.doFinal(blob, 38, blob.length - 38);
            try {
                JSONObject payload = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                validate(payload);
                return payload;
            } finally { Arrays.fill(plain, (byte) 0); }
        } finally { Arrays.fill(key, (byte) 0); }
    }

    private static byte[] derive(String password, byte[] salt, int memory, int time, int lanes) {
        if (memory < 8192 || memory > 262144 || time < 1 || time > 10 || lanes < 1 || lanes > 8)
            throw new IllegalArgumentException("保险库密钥参数无效");
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt)
                .withMemoryAsKB(memory).withIterations(time).withParallelism(lanes).build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] key = new byte[32];
        generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), key);
        return key;
    }

    static void validate(JSONObject payload) throws Exception {
        if (payload.getInt("version") != 1) throw new IllegalArgumentException("不支持的保险库版本");
        JSONArray accounts = payload.getJSONArray("accounts");
        if (accounts.length() > 10000) throw new IllegalArgumentException("账号过多");
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject a = accounts.getJSONObject(i);
            String id = a.getString("id");
            if (id.isEmpty() || !ids.add(id)) throw new IllegalArgumentException("账号 ID 无效");
            for (String field : new String[]{"issuer", "name", "email", "notes"})
                if (a.getString(field).getBytes(StandardCharsets.UTF_8).length > 4096) throw new IllegalArgumentException("账号字段过长");
            if (a.getString("secret").length() > 1024) throw new IllegalArgumentException("密钥过长");
            Totp.validate(a.getString("secret"), a.getString("algorithm"), a.getInt("digits"), a.getLong("period"));
            a.getLong("created"); a.getLong("updated");
        }
        JSONObject settings = payload.optJSONObject("settings");
        if (settings != null) {
            if (settings.optLong("autolock_seconds", 180) < 0 || settings.optLong("autolock_seconds", 180) > 2678400
                    || settings.optLong("clipboard_clear_seconds", 30) < 0 || settings.optLong("clipboard_clear_seconds", 30) > 86400)
                throw new IllegalArgumentException("设置时间无效");
            for (String field : new String[]{"webdav_url", "webdav_user", "webdav_password", "webdav_path", "webdav_etag"})
                if (settings.optString(field).getBytes(StandardCharsets.UTF_8).length > 4096) throw new IllegalArgumentException("设置内容过长");
        }
    }

    static JSONObject empty() throws Exception {
        JSONObject settings = new JSONObject();
        settings.put("webdav_url", "").put("webdav_user", "").put("webdav_password", "")
                .put("webdav_path", "/authenticator/vault.enc").put("webdav_etag", "")
                .put("autolock_seconds", 180).put("clipboard_clear_seconds", 30);
        return new JSONObject().put("version", 1).put("accounts", new JSONArray()).put("settings", settings);
    }
}
