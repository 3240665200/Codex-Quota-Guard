package com.pingche.web.authenticator;

import com.pingche.web.auth.DomainException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/** RFC 6238. No network calls, clock adjustments, secret export or Google account access. */
final class Totp {
    record Provision(String secret, String algorithm, int digits, int period) {
        @Override public String toString() { return "TotpProvision[redacted]"; }
    }
    private Totp() { }

    static Provision parse(String input) {
        if (input == null || input.isBlank() || input.length() > 4096) throw invalid();
        String value = input.trim();
        String algorithm = "SHA1";
        int digits = 6, period = 30;
        if (value.contains(":")) {
            try {
                URI uri = URI.create(value);
                if (!"otpauth".equalsIgnoreCase(uri.getScheme()) || !"totp".equalsIgnoreCase(uri.getHost())
                        || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null
                        || uri.getRawPath() == null || uri.getRawPath().length() < 2 || uri.getRawQuery() == null) throw invalid();
                Map<String, String> query = new HashMap<>();
                for (String entry : uri.getRawQuery().split("&", -1)) {
                    String[] pair = entry.split("=", 2);
                    if (pair.length != 2) throw invalid();
                    String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                    String parameter = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    if (!java.util.Set.of("secret", "issuer", "algorithm", "digits", "period").contains(name)
                            || query.putIfAbsent(name, parameter) != null) throw invalid();
                }
                value = query.get("secret");
                algorithm = query.getOrDefault("algorithm", "SHA1").toUpperCase(Locale.ROOT);
                digits = Integer.parseInt(query.getOrDefault("digits", "6"));
                period = Integer.parseInt(query.getOrDefault("period", "30"));
            } catch (RuntimeException ignored) { throw invalid(); }
        }
        if (!java.util.Set.of("SHA1", "SHA256", "SHA512").contains(algorithm)
                || digits != 6 && digits != 8 || period < 15 || period > 120) throw invalid();
        String normalized = normalize(value);
        byte[] bytes = decode(normalized);
        Arrays.fill(bytes, (byte) 0);
        return new Provision(normalized, algorithm, digits, period);
    }

    static String code(String secret, String algorithm, int digits, int period, Instant now) {
        if (!java.util.Set.of("SHA1", "SHA256", "SHA512").contains(algorithm)
                || digits != 6 && digits != 8 || period < 15 || period > 120 || now.getEpochSecond() < 0) throw invalid();
        byte[] key = decode(secret);
        try {
            Mac mac = Mac.getInstance("Hmac" + algorithm);
            mac.init(new SecretKeySpec(key, "Hmac" + algorithm));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(now.getEpochSecond() / period).array());
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24) | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8) | (digest[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%0" + digits + "d", binary % (digits == 6 ? 1_000_000 : 100_000_000));
        } catch (GeneralSecurityException ignored) { throw invalid(); }
        finally { Arrays.fill(key, (byte) 0); }
    }

    private static String normalize(String secret) {
        if (secret == null) throw invalid();
        String result = secret.replaceAll("[ \\t\\r\\n-]", "").toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z2-7]{16,205}={0,6}")) throw invalid();
        int padding = result.indexOf('=');
        if (padding >= 0 && result.length() % 8 != 0) throw invalid();
        return padding < 0 ? result : result.substring(0, padding);
    }

    private static byte[] decode(String secret) {
        String value = normalize(secret);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : value.toCharArray()) {
            int digit = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) { bits -= 8; bytes.write((buffer >> bits) & 0xff); }
        }
        if (bits >= 5 || (buffer & ((1 << bits) - 1)) != 0 || bytes.size() < 10 || bytes.size() > 128) throw invalid();
        return bytes.toByteArray();
    }

    static DomainException invalid() {
        return new DomainException("AUTHENTICATOR_CONFIG_INVALID", "请输入有效 Base32 密钥或 otpauth://totp 链接；不支持 HOTP 和 Google 迁移二维码。", HttpStatus.BAD_REQUEST);
    }
}
