package app.openauthenticator.android;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UpdateChecker {
    static final String PROJECT_URL = "https://github.com/Xunzi229/open-authenticator";
    private static final String LATEST_API = "https://api.github.com/repos/Xunzi229/open-authenticator/releases/latest";
    private static final Pattern VERSION = Pattern.compile("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$");

    static final class Result {
        final String latestVersion;
        final String releaseUrl;
        final boolean newer;
        Result(String latestVersion, String releaseUrl, boolean newer) {
            this.latestVersion = latestVersion; this.releaseUrl = releaseUrl; this.newer = newer;
        }
    }

    static Result check(String currentVersion) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_API).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "OpenAuthenticator-Android/" + currentVersion);
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IllegalStateException("GitHub 检查失败（HTTP " + status + "）");
            if (connection.getContentLengthLong() > 65536) throw new IllegalStateException("更新信息过大");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 65536) throw new IllegalStateException("更新信息过大");
                    output.write(buffer, 0, count);
                }
            }
            JSONObject release = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            String latest = release.getString("tag_name");
            String url = release.getString("html_url");
            URI target = new URI(url);
            if (!"https".equalsIgnoreCase(target.getScheme())
                    || !"github.com".equalsIgnoreCase(target.getHost())
                    || !target.getPath().startsWith("/Xunzi229/open-authenticator/releases/tag/"))
                throw new IllegalStateException("Release 地址无效");
            return new Result(latest, url, compare(latest, currentVersion) > 0);
        } finally { connection.disconnect(); }
    }

    static int compare(String first, String second) {
        long[] a = versionParts(first), b = versionParts(second);
        for (int i = 0; i < 3; i++) {
            int result = Long.compare(a[i], b[i]);
            if (result != 0) return result;
        }
        return 0;
    }

    private static long[] versionParts(String version) {
        Matcher match = VERSION.matcher(version.trim());
        if (!match.matches()) throw new IllegalArgumentException("版本号格式无效：" + version);
        return new long[]{Long.parseLong(match.group(1)), Long.parseLong(match.group(2)), Long.parseLong(match.group(3))};
    }
}
