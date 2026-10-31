package app.openauthenticator.android;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class OtpUri {
    static List<JSONObject> parse(String input) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        String whole = input.trim();
        if (whole.startsWith("[")) {
            JSONArray array = new JSONArray(whole);
            for (int i = 0; i < array.length(); i++) result.add(fromJson(array.getJSONObject(i)));
            if (result.isEmpty()) throw new IllegalArgumentException("没有找到账号");
            return result;
        }
        if (whole.startsWith("{")) { result.add(fromJson(new JSONObject(whole))); return result; }
        for (String line : input.split("\\r?\\n")) {
            String text = line.trim(); if (text.isEmpty()) continue;
            if (text.startsWith("otpauth-migration://")) result.addAll(migration(text));
            else if (text.startsWith("otpauth://")) result.add(single(text));
            else throw new IllegalArgumentException("不支持的导入内容");
        }
        if (result.isEmpty()) throw new IllegalArgumentException("没有找到账号");
        return result;
    }

    private static JSONObject single(String text) throws Exception {
        Uri uri = Uri.parse(text);
        if (!"totp".equalsIgnoreCase(uri.getHost())) throw new IllegalArgumentException("只支持 TOTP 链接");
        String secret = uri.getQueryParameter("secret"); if (secret == null) throw new IllegalArgumentException("缺少密钥");
        String issuer = uri.getQueryParameter("issuer"); if (issuer == null) issuer = "";
        String name = Uri.decode(uri.getEncodedPath() == null ? "" : uri.getEncodedPath().replaceFirst("^/", ""));
        int colon = name.indexOf(':');
        if (colon >= 0) { if (issuer.isEmpty()) issuer = name.substring(0, colon); name = name.substring(colon + 1); }
        String algorithm = uri.getQueryParameter("algorithm"); if (algorithm == null) algorithm = "SHA1";
        String digitsText = uri.getQueryParameter("digits"), periodText = uri.getQueryParameter("period");
        int digits = digitsText == null ? 6 : Integer.parseInt(digitsText);
        long period = periodText == null ? 30 : Long.parseLong(periodText);
        return account(issuer, name, secret, algorithm, digits, period);
    }

    private static JSONObject account(String issuer, String name, String secret, String algorithm, int digits, long period) throws Exception {
        secret = Totp.normalize(secret); algorithm = algorithm.toUpperCase().replace("-", "");
        Totp.validate(secret, algorithm, digits, period);
        String email = name.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") ? name : "";
        return new JSONObject().put("id", "").put("issuer", issuer).put("name", name).put("email", email)
                .put("notes", "").put("secret", secret).put("algorithm", algorithm).put("digits", digits)
                .put("period", period).put("created", 0).put("updated", 0);
    }

    private static JSONObject fromJson(JSONObject item) throws Exception {
        if (item.has("uri")) return single(item.getString("uri"));
        return account(item.optString("issuer"), item.optString("name"), item.getString("secret"),
                item.optString("algorithm", "SHA1"), item.optInt("digits", 6), item.optLong("period", 30));
    }

    static String export(JSONObject a) throws Exception {
        String issuer = a.optString("issuer"), name = a.optString("name");
        String label = issuer.isEmpty() ? name : issuer + ":" + name;
        return "otpauth://totp/" + Uri.encode(label) + "?secret=" + Uri.encode(a.getString("secret"))
                + "&issuer=" + Uri.encode(issuer) + "&algorithm=" + a.getString("algorithm")
                + "&digits=" + a.getInt("digits") + "&period=" + a.getLong("period");
    }

    private static List<JSONObject> migration(String text) throws Exception {
        String data = Uri.parse(text).getQueryParameter("data");
        if (data == null || data.length() > 1400000) throw new IllegalArgumentException("迁移二维码无效或过大");
        byte[] raw;
        try { raw = Base64.getDecoder().decode(data.replace(' ', '+')); }
        catch (IllegalArgumentException error) { raw = Base64.getUrlDecoder().decode(data.replace(' ', '+')); }
        if (raw.length > 1024 * 1024) throw new IllegalArgumentException("迁移数据过大");
        Proto p = new Proto(raw); List<JSONObject> out = new ArrayList<>(); long batchSize = 1, batchIndex = 0;
        while (p.more()) {
            long tag = p.varint(); int field = (int) (tag >> 3), wire = (int) (tag & 7);
            if (wire == 2) {
                byte[] value = p.bytes(); if (field == 1) { JSONObject a = migrationAccount(value); if (a != null) out.add(a); }
            } else if (wire == 0) { long n = p.varint(); if (field == 3) batchSize = n; if (field == 4) batchIndex = n; }
            else p.skip(wire);
        }
        if (out.isEmpty() || batchSize < 1 || batchIndex >= batchSize) throw new IllegalArgumentException("迁移二维码中没有有效账号");
        return out;
    }

    private static JSONObject migrationAccount(byte[] data) throws Exception {
        Proto p = new Proto(data); byte[] secret = null; String name = "", issuer = ""; long algorithm = 1, digits = 1, type = 0;
        while (p.more()) {
            long tag = p.varint(); int field = (int) (tag >> 3), wire = (int) (tag & 7);
            if (wire == 2) {
                byte[] value = p.bytes();
                if (field == 1) secret = value;
                if (field == 2) name = new String(value, StandardCharsets.UTF_8);
                if (field == 3) issuer = new String(value, StandardCharsets.UTF_8);
            } else if (wire == 0) {
                long n = p.varint(); if (field == 4) algorithm = n; if (field == 5) digits = n; if (field == 6) type = n;
            } else p.skip(wire);
        }
        if (secret == null || secret.length == 0) return null;
        if (type == 1) throw new IllegalArgumentException("暂不支持 HOTP 账号");
        if (type != 0 && type != 2) throw new IllegalArgumentException("未知 OTP 类型");
        String algo = algorithm == 1 ? "SHA1" : algorithm == 2 ? "SHA256" : algorithm == 3 ? "SHA512" : null;
        int count = digits == 1 || digits == 6 ? 6 : digits == 2 || digits == 8 ? 8 : 0;
        if (algo == null || count == 0) throw new IllegalArgumentException("迁移数据算法或位数无效");
        return account(issuer, name, Totp.encode(secret), algo, count, 30);
    }

    private static final class Proto {
        final byte[] data; int pos;
        Proto(byte[] data) { this.data = data; }
        boolean more() { return pos < data.length; }
        long varint() {
            long value = 0; int shift = 0;
            while (pos < data.length && shift < 70) {
                int b = data[pos++] & 255; value |= (long) (b & 127) << shift;
                if ((b & 128) == 0) return value; shift += 7;
            }
            throw new IllegalArgumentException("迁移数据损坏");
        }
        byte[] bytes() {
            long length = varint(); if (length < 0 || length > data.length - pos) throw new IllegalArgumentException("迁移数据损坏");
            byte[] value = java.util.Arrays.copyOfRange(data, pos, pos + (int) length); pos += (int) length; return value;
        }
        void skip(int wire) {
            if (wire == 1) pos += 8; else if (wire == 5) pos += 4; else throw new IllegalArgumentException("迁移数据字段无效");
            if (pos > data.length) throw new IllegalArgumentException("迁移数据损坏");
        }
    }
}
