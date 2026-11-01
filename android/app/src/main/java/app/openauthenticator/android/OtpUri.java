package app.openauthenticator.android;

import android.net.Uri;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
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

    static final class MigrationPage {
        final String uri;
        final int count;
        MigrationPage(String uri, int count) { this.uri = uri; this.count = count; }
    }

    static final class MigrationExport {
        final List<MigrationPage> pages;
        final int excluded;
        MigrationExport(List<MigrationPage> pages, int excluded) {
            this.pages = pages; this.excluded = excluded;
        }
    }

    /** Google Authenticator migration format: at most ten accounts per QR, shrinking if needed. */
    static MigrationExport migrationExport(JSONArray accounts) throws Exception {
        if (accounts.length() == 0) throw new IllegalArgumentException("没有可迁移的账号");
        List<JSONObject> supported = new ArrayList<>();
        int excluded = 0;
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.getJSONObject(i);
            if (account.getLong("period") == 30) supported.add(account);
            else excluded++;
        }
        if (supported.isEmpty()) return new MigrationExport(new ArrayList<>(), excluded);
        long batchId = new SecureRandom().nextInt() & 0xffffffffL;
        for (int perPage = Math.min(10, supported.size()); perPage >= 1; perPage--) {
            List<MigrationPage> pages = new ArrayList<>();
            int total = (supported.size() + perPage - 1) / perPage;
            boolean fits = true;
            for (int start = 0, index = 0; start < supported.size(); start += perPage, index++) {
                int end = Math.min(start + perPage, supported.size());
                String uri = migrationUri(supported.subList(start, end), index, total, batchId);
                try { new QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 1, 1); }
                catch (WriterException error) { fits = false; break; }
                pages.add(new MigrationPage(uri, end - start));
            }
            if (fits) return new MigrationExport(pages, excluded);
        }
        throw new IllegalArgumentException("账号内容过长，无法生成迁移二维码");
    }

    private static String migrationUri(List<JSONObject> accounts, int index, int total, long batchId) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (JSONObject account : accounts) writeBytes(payload, 1, migrationAccountBytes(account));
        writeNumber(payload, 2, 1);
        writeNumber(payload, 3, total);
        writeNumber(payload, 4, index);
        writeNumber(payload, 5, batchId);
        String encoded = Base64.getEncoder().encodeToString(payload.toByteArray());
        return "otpauth-migration://offline?data=" + encoded.replace("+", "%2B");
    }

    private static byte[] migrationAccountBytes(JSONObject account) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeBytes(output, 1, Totp.decode(Totp.normalize(account.getString("secret"))));
        String name = account.optString("name"), issuer = account.optString("issuer");
        if (!name.isEmpty()) writeBytes(output, 2, name.getBytes(StandardCharsets.UTF_8));
        if (!issuer.isEmpty()) writeBytes(output, 3, issuer.getBytes(StandardCharsets.UTF_8));
        String algorithm = account.getString("algorithm").toUpperCase(java.util.Locale.ROOT).replace("-", "");
        writeNumber(output, 4, "SHA256".equals(algorithm) ? 2 : "SHA512".equals(algorithm) ? 3 : 1);
        writeNumber(output, 5, account.getInt("digits") == 8 ? 2 : 1);
        writeNumber(output, 6, 2);
        return output.toByteArray();
    }

    private static void writeBytes(ByteArrayOutputStream output, int field, byte[] value) {
        writeVarint(output, (field << 3) | 2);
        writeVarint(output, value.length);
        output.write(value, 0, value.length);
    }

    private static void writeNumber(ByteArrayOutputStream output, int field, long value) {
        writeVarint(output, field << 3);
        writeVarint(output, value);
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        while ((value & ~0x7fL) != 0) {
            output.write(((int) value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.write((int) value);
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
