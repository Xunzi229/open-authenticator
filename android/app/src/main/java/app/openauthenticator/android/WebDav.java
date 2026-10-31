package app.openauthenticator.android;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

final class WebDav {
    static final class Download {
        final byte[] bytes; final String etag;
        Download(byte[] bytes, String etag) { this.bytes = bytes; this.etag = etag; }
    }

    private static URI target(String base, String path) throws Exception {
        if (base.trim().isEmpty()) throw new IllegalArgumentException("请填写 WebDAV 地址");
        URI root = new URI(base.trim().endsWith("/") ? base.trim() : base.trim() + "/");
        if (root.getUserInfo() != null) throw new IllegalArgumentException("请不要把凭据写入地址");
        boolean loopback = "localhost".equals(root.getHost()) || "127.0.0.1".equals(root.getHost()) || "::1".equals(root.getHost());
        if (!"https".equalsIgnoreCase(root.getScheme()) && !(loopback && "http".equalsIgnoreCase(root.getScheme())))
            throw new IllegalArgumentException("WebDAV 必须使用 HTTPS");
        if (root.getHost() == null || root.getFragment() != null) throw new IllegalArgumentException("WebDAV 地址无效");
        URI result = root.resolve(path.replaceFirst("^/+", ""));
        if (!root.getScheme().equalsIgnoreCase(result.getScheme()) || !root.getHost().equalsIgnoreCase(result.getHost()) || root.getPort() != result.getPort())
            throw new IllegalArgumentException("WebDAV 路径不能跳转到其他服务器");
        return result;
    }

    private static HttpURLConnection request(URI uri, String method, String user, String pass) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(25000); connection.setReadTimeout(25000);
        connection.setRequestMethod(method);
        if (!user.isEmpty()) connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8)));
        return connection;
    }

    private static byte[] readLimited(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192]; int n;
            while ((n = stream.read(chunk)) != -1) {
                if (out.size() + n > VaultCodec.LIMIT) throw new IllegalArgumentException("远程保险库超过 16 MiB");
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder("sha256:"); for (byte b : digest) hex.append(String.format("%02x", b & 255)); return hex.toString();
    }

    private static String strongEtag(HttpURLConnection connection) {
        String value = connection.getHeaderField("ETag");
        return value == null || value.trim().isEmpty() || value.trim().startsWith("W/") ? null : value.trim();
    }

    static Download get(String base, String path, String user, String pass) throws Exception {
        Download found = getOptional(target(base, path), user, pass);
        if (found == null) throw new IllegalStateException("远程保险库不存在");
        return found;
    }

    private static Download getOptional(URI uri, String user, String pass) throws Exception {
        HttpURLConnection c = request(uri, "GET", user, pass);
        try {
            int status = c.getResponseCode(); if (status == 404) return null;
            if (status != 200) throw failure("下载", status);
            if (c.getContentLengthLong() > VaultCodec.LIMIT) throw new IllegalArgumentException("远程保险库超过 16 MiB");
            String etag = strongEtag(c); byte[] blob = readLimited(c.getInputStream());
            if (blob.length == 0) throw new IllegalArgumentException("远程文件是空的");
            return new Download(blob, etag == null ? hash(blob) : etag);
        } finally { c.disconnect(); }
    }

    private static IllegalStateException failure(String op, int code) {
        if (code == 401) return new IllegalStateException("WebDAV " + op + "认证失败");
        if (code == 412) return new IllegalStateException("远程文件已被其他设备修改，请先拉取");
        return new IllegalStateException("WebDAV " + op + "失败 (" + code + ")");
    }

    private static void ensureCollections(String base, String path, String user, String pass) throws Exception {
        String[] parts = path.replaceAll("^/+|/+$", "").split("/");
        String relative = "";
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].isEmpty() || parts[i].equals(".") || parts[i].equals("..")) throw new IllegalArgumentException("WebDAV 路径无效");
            relative += parts[i] + "/";
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(target(base, relative).toString());
            if (!user.isEmpty()) builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8)));
            okhttp3.RequestBody empty = okhttp3.RequestBody.create(new byte[0], null);
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().followRedirects(false).callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build();
            try (okhttp3.Response response = client.newCall(builder.method("MKCOL", empty).build()).execute()) {
                int status = response.code(); if (status != 200 && status != 201 && status != 204 && status != 405) throw failure("创建目录", status);
            }
        }
    }

    static String put(JSONObject settings, byte[] data) throws Exception {
        String base = settings.optString("webdav_url"), path = settings.optString("webdav_path", "/authenticator/vault.enc");
        String user = settings.optString("webdav_user"), pass = settings.optString("webdav_password");
        String expected = settings.optString("webdav_etag");
        if (data.length > VaultCodec.LIMIT) throw new IllegalArgumentException("保险库文件过大");
        URI uri = target(base, path); ensureCollections(base, path, user, pass);
        Download current = getOptional(uri, user, pass);
        String ifMatch = null; boolean ifNone = false;
        if (expected.isEmpty()) {
            if (current != null) {
                if (java.util.Arrays.equals(current.bytes, data)) return current.etag;
                throw new IllegalStateException("远程保险库已存在，请先拉取确认内容");
            }
            ifNone = true;
        } else if (expected.startsWith("sha256:")) {
            if (current == null || !hash(current.bytes).equals(expected)) throw new IllegalStateException("远程文件已变化，请先拉取");
            if (!current.etag.startsWith("sha256:")) ifMatch = current.etag;
        } else ifMatch = expected;
        HttpURLConnection c = request(uri, "PUT", user, pass);
        try {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/octet-stream");
            if (ifNone) c.setRequestProperty("If-None-Match", "*");
            if (ifMatch != null) c.setRequestProperty("If-Match", ifMatch);
            try (java.io.OutputStream out = c.getOutputStream()) { out.write(data); }
            int status = c.getResponseCode();
            if (status != 200 && status != 201 && status != 204 && status != 207) throw failure("上传", status);
            String etag = strongEtag(c); if (etag != null) return etag;
        } finally { c.disconnect(); }
        Download uploaded = getOptional(uri, user, pass);
        if (uploaded == null || !java.util.Arrays.equals(uploaded.bytes, data)) throw new IllegalStateException("上传后远程校验失败");
        return uploaded.etag;
    }
}
