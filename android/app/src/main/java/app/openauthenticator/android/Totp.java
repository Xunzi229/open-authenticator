package app.openauthenticator.android;

import java.nio.ByteBuffer;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class Totp {
    static String normalize(String value) {
        String s = value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT).replaceAll("=+$", "");
        if (s.isEmpty() || !s.matches("[A-Z2-7]+")) throw new IllegalArgumentException("密钥不是有效的 Base32");
        decode(s);
        return s;
    }

    static void validate(String secret, String algorithm, int digits, long period) {
        normalize(secret);
        String a = algorithm.toUpperCase(Locale.ROOT).replace("-", "");
        if (!a.equals("SHA1") && !a.equals("SHA256") && !a.equals("SHA512")) throw new IllegalArgumentException("不支持的验证码算法");
        if (digits != 6 && digits != 8) throw new IllegalArgumentException("验证码只能是 6 或 8 位");
        if (period < 1 || period > 86400) throw new IllegalArgumentException("验证码周期无效");
    }

    static byte[] decode(String base32) {
        String s = base32.replaceAll("=+$", "");
        if (s.length() % 8 == 1 || s.length() % 8 == 3 || s.length() % 8 == 6)
            throw new IllegalArgumentException("密钥不是有效的 Base32");
        byte[] out = new byte[s.length() * 5 / 8];
        int buffer = 0, bits = 0, pos = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v = c >= 'A' && c <= 'Z' ? c - 'A' : c >= '2' && c <= '7' ? c - '2' + 26 : -1;
            if (v < 0) throw new IllegalArgumentException("密钥不是有效的 Base32");
            buffer = (buffer << 5) | v; bits += 5;
            if (bits >= 8) { bits -= 8; out[pos++] = (byte) (buffer >> bits); buffer &= (1 << bits) - 1; }
        }
        if (pos == 0 || buffer != 0) throw new IllegalArgumentException("密钥不是有效的 Base32");
        return out;
    }

    static String encode(byte[] bytes) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder out = new StringBuilder(); int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 255); bits += 8;
            while (bits >= 5) { bits -= 5; out.append(alphabet.charAt((buffer >> bits) & 31)); buffer &= (1 << bits) - 1; }
        }
        if (bits > 0) out.append(alphabet.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    static String code(String secret, String algorithm, int digits, long period, long seconds) throws Exception {
        validate(secret, algorithm, digits, period);
        byte[] key = decode(normalize(secret));
        String a = algorithm.toUpperCase(Locale.ROOT).replace("-", "");
        Mac mac = Mac.getInstance("Hmac" + a);
        mac.init(new SecretKeySpec(key, "Hmac" + a));
        byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(seconds / period).array());
        int offset = h[h.length - 1] & 15;
        long n = ((h[offset] & 127L) << 24) | ((h[offset + 1] & 255L) << 16)
                | ((h[offset + 2] & 255L) << 8) | (h[offset + 3] & 255L);
        long mod = digits == 8 ? 100000000L : 1000000L;
        return String.format(Locale.ROOT, "%0" + digits + "d", n % mod);
    }
}
